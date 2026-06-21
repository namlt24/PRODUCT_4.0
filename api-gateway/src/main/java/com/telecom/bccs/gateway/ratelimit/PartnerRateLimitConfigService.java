package com.telecom.bccs.gateway.ratelimit;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * BƯỚC 2 (nguồn cấu hình) — Nạp cấu hình TPS theo đối tác từ DB, giữ trong bộ nhớ và
 * LÀM TƯƠI ĐỊNH KỲ (mặc định 15s). Nhờ đó:
 *  - Đường xử lý request KHÔNG chạm DB (chỉ tra Map in-memory) → không blocking event-loop.
 *  - Đổi hạn mức trong DB sẽ tự áp dụng sau ≤ refreshMs giây, KHÔNG cần restart (động).
 *
 * Thứ tự ưu tiên khi tra hạn mức (cụ thể thắng):
 *   1) partner_rate_limit theo (partner, api_scope = routeId)   -- override theo từng API
 *   2) partner_rate_limit theo (partner, api_scope = 'DEFAULT') -- override chung cho đối tác
 *   3) hạn mức của TIER mà đối tác thuộc về                     -- mặc định theo gói
 *   4) hạn mức mặc định hệ thống (RateLimitProperties)          -- fallback cuối
 * Đối tác SUSPENDED → trả hạn mức 0 (chặn hoàn toàn).
 */
@Service
public class PartnerRateLimitConfigService {

    private static final Logger log = LoggerFactory.getLogger(PartnerRateLimitConfigService.class);
    public static final String SCOPE_DEFAULT = "DEFAULT";

    private final JdbcTemplate jdbc;
    private final RateLimitProperties props;

    /** Snapshot bất biến, đổi nguyên cụm khi refresh (đọc lock-free). */
    private volatile Map<String, PartnerConfig> snapshot = Map.of();

    public PartnerRateLimitConfigService(JdbcTemplate jdbc, RateLimitProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** Hạn mức đã giải quyết cho 1 (đối tác, route). */
    public record Limit(int replenishRate, int burstCapacity, int requestedTokens, String source) {}

    private record PartnerConfig(String status, Limit tierLimit, Map<String, Limit> overrides,
                                 String apiKeyHash) {}

    public Limit systemDefault() {
        return new Limit(props.getDefaultReplenishRate(), props.getDefaultBurstCapacity(),
                props.getDefaultRequestedTokens(), "system-default");
    }

    /** Hạn mức "chặn" cho đối tác bị treo. */
    private static final Limit BLOCKED = new Limit(0, 0, 1, "suspended");

    /**
     * XÁC THỰC đối tác bằng cặp (client_id, API_KEY thô). Thuần in-memory, an toàn trên event-loop.
     * So khớp SHA-256(rawApiKey) với api_key_hash đã nạp; so sánh HẰNG THỜI GIAN (chống timing attack).
     * Đối tác lạ / chưa cấu hình key / đang SUSPENDED → false.
     */
    public boolean authenticate(String partnerCode, String rawApiKey) {
        if (partnerCode == null || rawApiKey == null || rawApiKey.isBlank()) {
            return false;
        }
        PartnerConfig pc = snapshot.get(partnerCode);
        if (pc == null || pc.apiKeyHash() == null || pc.apiKeyHash().isBlank()) {
            return false;
        }
        if ("SUSPENDED".equalsIgnoreCase(pc.status())) {
            return false;
        }
        byte[] expected = pc.apiKeyHash().toLowerCase().getBytes(StandardCharsets.UTF_8);
        byte[] actual = sha256Hex(rawApiKey).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e); // không xảy ra trên JVM chuẩn
        }
    }

    /**
     * Tra hạn mức cho đối tác trên một route. Thuần in-memory, an toàn gọi trên event-loop.
     */
    public Limit resolve(String partnerCode, String routeId) {
        PartnerConfig pc = snapshot.get(partnerCode);
        if (pc == null) {
            return systemDefault();                 // đối tác lạ / chưa khai báo
        }
        if ("SUSPENDED".equalsIgnoreCase(pc.status())) {
            return BLOCKED;
        }
        Limit byApi = pc.overrides().get(routeId);
        if (byApi != null) return byApi;
        Limit byDefault = pc.overrides().get(SCOPE_DEFAULT);
        if (byDefault != null) return byDefault;
        if (pc.tierLimit() != null) return pc.tierLimit();
        return systemDefault();
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${gateway.rate-limit.refresh-ms:15000}")
    public void refresh() {
        try {
            // 1) Hạn mức theo tier
            Map<String, Limit> tiers = new HashMap<>();
            jdbc.query("SELECT tier_code, replenish_rate, burst_capacity, requested_tokens FROM rate_limit_tier",
                    rs -> {
                        tiers.put(rs.getString("tier_code"), new Limit(
                                rs.getInt("replenish_rate"), rs.getInt("burst_capacity"),
                                rs.getInt("requested_tokens"), "tier:" + rs.getString("tier_code")));
                    });

            // 2) Override theo đối tác (+ scope), chỉ lấy bản đang hiệu lực
            Map<String, Map<String, Limit>> overrides = new HashMap<>();
            jdbc.query("""
                    SELECT partner_code, api_scope, replenish_rate, burst_capacity, requested_tokens
                    FROM partner_rate_limit
                    WHERE enabled = 1
                      AND (valid_from IS NULL OR valid_from <= NOW())
                      AND (valid_to   IS NULL OR valid_to   >= NOW())
                    """, rs -> {
                overrides.computeIfAbsent(rs.getString("partner_code"), k -> new HashMap<>())
                        .put(rs.getString("api_scope"), new Limit(
                                rs.getInt("replenish_rate"), rs.getInt("burst_capacity"),
                                rs.getInt("requested_tokens"),
                                "partner:" + rs.getString("partner_code") + ":" + rs.getString("api_scope")));
            });

            // 3) Đối tác + tier + hash API key (để xác thực)
            Map<String, PartnerConfig> next = new HashMap<>();
            jdbc.query("SELECT code, status, tier_code, api_key_hash FROM partner", rs -> {
                String code = rs.getString("code");
                next.put(code, new PartnerConfig(
                        rs.getString("status"),
                        tiers.get(rs.getString("tier_code")),
                        overrides.getOrDefault(code, Map.of()),
                        rs.getString("api_key_hash")));
            });
            // Đối tác có override nhưng không có bản ghi partner (đề phòng) vẫn được nạp (không có key → không auth được)
            overrides.forEach((code, ov) -> next.putIfAbsent(code, new PartnerConfig("ACTIVE", null, ov, null)));

            this.snapshot = next;
            log.debug("Rate-limit config refreshed: {} partners, {} tiers", next.size(), tiers.size());
        } catch (Exception e) {
            // DB lỗi → GIỮ snapshot cũ (fail-safe), không làm sập gateway
            log.warn("Refresh rate-limit config failed, keeping previous snapshot: {}", e.getMessage());
        }
    }
}
