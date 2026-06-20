package com.telecom.bccs.gateway.config;

import com.telecom.bccs.gateway.ratelimit.RateLimitProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

/**
 * Hạ tầng cho Dynamic Rate Limiting theo đối tác:
 *  - Bật {@link EnableScheduling} để làm tươi cấu hình đối tác định kỳ.
 *  - Bật {@link RateLimitProperties} (giới hạn mặc định + chu kỳ refresh).
 *  - Cung cấp Lua script <b>token bucket</b> chạy ATOMIC trong Redis (cùng thuật toán
 *    Spring Cloud Gateway dùng), nhưng tham số được nạp động theo đối tác ở
 *    {@code PartnerRateLimiter}.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimiterConfig {

    /**
     * Token bucket:
     *   KEYS[1] = tokens_key, KEYS[2] = timestamp_key
     *   ARGV[1] = rate (tokens/giây), ARGV[2] = capacity (burst),
     *   ARGV[3] = now (epoch giây),    ARGV[4] = requested (token tiêu thụ)
     * Trả về { allowed (1/0), tokens_left }.
     */
    private static final String TOKEN_BUCKET_LUA = """
            local tokens_key = KEYS[1]
            local timestamp_key = KEYS[2]
            local rate = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])
            local fill_time = capacity/rate
            local ttl = math.floor(fill_time*2)
            local last_tokens = tonumber(redis.call("get", tokens_key))
            if last_tokens == nil then last_tokens = capacity end
            local last_refreshed = tonumber(redis.call("get", timestamp_key))
            if last_refreshed == nil then last_refreshed = 0 end
            local delta = math.max(0, now-last_refreshed)
            local filled_tokens = math.min(capacity, last_tokens+(delta*rate))
            local allowed = filled_tokens >= requested
            local new_tokens = filled_tokens
            local allowed_num = 0
            if allowed then
              new_tokens = filled_tokens - requested
              allowed_num = 1
            end
            if ttl > 0 then
              redis.call("setex", tokens_key, ttl, new_tokens)
              redis.call("setex", timestamp_key, ttl, now)
            end
            return { allowed_num, new_tokens }
            """;

    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> partnerRateLimiterScript() {
        return RedisScript.of(TOKEN_BUCKET_LUA, List.class);
    }
}
