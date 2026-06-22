package com.telecom.bccs.gateway.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BƯỚC 2 — Custom Rate Limiter động theo đối tác.
 *
 * <p>Với mỗi request, filter gọi {@link #isAllowed(String routeId, String partnerKey)}:
 *   1) Tra hạn mức (replenishRate/burstCapacity) của đối tác trên route đó từ
 *      {@link PartnerRateLimitConfigService} (in-memory, không chạm DB).
 *   2) Chạy thuật toán <b>token bucket</b> trong Redis bằng Lua script ATOMIC với THAM SỐ RIÊNG
 *      của đối tác → mỗi đối tác có một xô token độc lập (key có hash-tag để cùng slot trên cluster).
 *
 * <p>Khác với {@code RedisRateLimiter} mặc định (tham số tĩnh theo route trong YAML), ở đây tham số
 * lấy động theo đối tác. Redis lỗi → <b>fail-open</b> (cho qua) để không tự biến rate-limit thành SPOF.
 */
// @Primary: Spring Cloud Gateway tự tạo sẵn 'redisRateLimiter'; đánh dấu bean này là mặc định để
// RequestRateLimiterGatewayFilterFactory tiêm đúng 1 RateLimiter (tránh lỗi "2 beans found").
@Primary
@Component("partnerRateLimiter")
public class PartnerRateLimiter implements RateLimiter<PartnerRateLimiter.Config> {

    private static final Logger log = LoggerFactory.getLogger(PartnerRateLimiter.class);

    private final ReactiveStringRedisTemplate redis;
    private final RedisScript<List> script;
    private final PartnerRateLimitConfigService configService;
    private final Map<String, Config> config = new ConcurrentHashMap<>();

    public PartnerRateLimiter(ReactiveStringRedisTemplate redis,
                              RedisScript<List> partnerRateLimiterScript,
                              PartnerRateLimitConfigService configService) {
        this.redis = redis;
        this.script = partnerRateLimiterScript;
        this.configService = configService;
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        PartnerRateLimitConfigService.Limit limit = configService.resolve(id, routeId);

        // Đối tác bị treo (rate=0) → chặn ngay, không cần chạm Redis
        if (limit.replenishRate() <= 0) {
            return Mono.just(new Response(false, headers(limit, 0)));
        }

        List<String> keys = keys(id, routeId);
        List<String> args = List.of(
                Integer.toString(limit.replenishRate()),
                Integer.toString(limit.burstCapacity()),
                Long.toString(Instant.now().getEpochSecond()),
                Integer.toString(limit.requestedTokens()));

        return redis.execute(script, keys, args)
                .next()
                .map(result -> {
                    boolean allowed = toLong(result.get(0)) == 1L;
                    long tokensLeft = toLong(result.get(1));
                    return new Response(allowed, headers(limit, tokensLeft));
                })
                .onErrorResume(e -> {
                    // Redis sự cố → cho qua (fail-open), gateway vẫn phục vụ
                    log.warn("Rate limiter Redis error for {}:{} -> fail-open: {}", id, routeId, e.getMessage());
                    return Mono.just(new Response(true, headers(limit, -1)));
                });
    }

    private List<String> keys(String partnerKey, String routeId) {
        // hash-tag {…} để 2 key cùng slot trên Redis Cluster
        String prefix = "rate_limit.{" + partnerKey + ":" + routeId + "}";
        return List.of(prefix + ".tokens", prefix + ".timestamp");
    }

    private Map<String, String> headers(PartnerRateLimitConfigService.Limit limit, long remaining) {
        Map<String, String> h = new HashMap<>();
        h.put("X-RateLimit-Remaining", Long.toString(remaining));
        h.put("X-RateLimit-Replenish-Rate", Integer.toString(limit.replenishRate()));
        h.put("X-RateLimit-Burst-Capacity", Integer.toString(limit.burstCapacity()));
        h.put("X-RateLimit-Policy-Source", limit.source());
        return h;
    }

    private static long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(o));
    }

    // ----- StatefulConfigurable: không dùng config theo route trong YAML (cấu hình lấy từ DB) -----
    @Override
    public Map<String, Config> getConfig() {
        return config;
    }

    @Override
    public Class<Config> getConfigClass() {
        return Config.class;
    }

    @Override
    public Config newConfig() {
        return new Config();
    }

    public static class Config {
    }
}
