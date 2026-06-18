package com.telecom.bccs.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Cache-aside coordinator across two levels:
 *
 * <pre>
 *   read():  L1 (Caffeine) -> L2 (Redis) -> loader (DB)
 *   - On L2 hit, value is back-filled into L1.
 *   - On miss, loader runs, the result is written to BOTH levels.
 * </pre>
 *
 * Protections against the classic cache failure modes:
 * <ul>
 *   <li><b>Cache Avalanche</b>: every Redis TTL is base + random jitter so a 3rd-party bulk
 *       scan that populates millions of keys at once does NOT make them all expire in the
 *       same second and stampede the DB.</li>
 *   <li><b>Cache Penetration</b>: a deliberate {@code NULL_MARKER} is cached (with a short TTL)
 *       for ids that don't exist, so repeated requests for bad/garbage ids never reach the DB.</li>
 *   <li><b>Redis outage</b>: all Redis operations are wrapped; on failure the service logs and
 *       falls through to L1/loader (fail-open) so the platform stays up when Redis is down.</li>
 * </ul>
 */
@Service
public class TwoLevelCacheService {

    private static final Logger log = LoggerFactory.getLogger(TwoLevelCacheService.class);

    /** Sentinel stored to represent "this id was looked up and does not exist". */
    public static final String NULL_MARKER = "__NULL__";

    private final RedisTemplate<String, Object> redis;
    private final ObjectMapper objectMapper;

    @Value("${cache.l2.base-ttl-seconds:600}")
    private long baseTtlSeconds;

    @Value("${cache.l2.jitter-seconds:120}")
    private long jitterSeconds;

    @Value("${cache.l2.null-ttl-seconds:60}")
    private long nullTtlSeconds;

    /**
     * One Caffeine instance per logical cache name. Kept here (not Spring's CacheManager) so the
     * service has full programmatic control over the L1<->L2 flow and null markers.
     */
    private final Cache<String, Object> l1 = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .recordStats()
            .build();

    public TwoLevelCacheService(RedisTemplate<String, Object> redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * Reads through both cache levels. Returns {@code null} if the entity genuinely does not exist
     * (a cached NULL_MARKER is treated as "absent" by the caller, without hitting the DB again).
     *
     * @param namespace logical group, e.g. "product" / "offer"
     * @param key       business id
     * @param type      expected value type
     * @param loader    DB loader; returns null when the entity is absent
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String namespace, String key, Class<T> type, Supplier<T> loader) {
        String cacheKey = namespace + ":" + key;

        // ---- L1 ----
        Object l1Value = l1.getIfPresent(cacheKey);
        if (l1Value != null) {
            return unwrap(l1Value, type);
        }

        // ---- L2 (Redis) — fail-open on error ----
        Object l2Value = safeRedisGet(cacheKey);
        if (l2Value != null) {
            l1.put(cacheKey, l2Value);              // back-fill L1
            return unwrap(l2Value, type);
        }

        // ---- Loader (DB) ----
        T loaded = loader.get();
        if (loaded == null) {
            // Penetration guard: remember the miss briefly in both levels
            l1.put(cacheKey, NULL_MARKER);
            safeRedisPut(cacheKey, NULL_MARKER, nullTtlSeconds);
            return null;
        }

        l1.put(cacheKey, loaded);
        safeRedisPut(cacheKey, loaded, randomizedTtl());   // avalanche guard
        return loaded;
    }

    /** Invalidate a single key in both levels (called by the Kafka invalidation consumer). */
    public void evict(String namespace, String key) {
        String cacheKey = namespace + ":" + key;
        l1.invalidate(cacheKey);
        try {
            redis.delete(cacheKey);
        } catch (RuntimeException e) {
            log.warn("Redis evict failed for {} (continuing): {}", cacheKey, e.getMessage());
        }
    }

    /** Invalidate everything in a namespace (e.g. after a bulk import). */
    public void evictNamespace(String namespace) {
        l1.asMap().keySet().removeIf(k -> k.startsWith(namespace + ":"));
        try {
            var keys = redis.keys(namespace + ":*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (RuntimeException e) {
            log.warn("Redis namespace evict failed for {}: {}", namespace, e.getMessage());
        }
    }

    private <T> T unwrap(Object value, Class<T> type) {
        if (NULL_MARKER.equals(value)) {
            return null;   // cached "does not exist"
        }
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        // Redis JSON for a final record deserializes to a Map; coerce to the target type.
        return objectMapper.convertValue(value, type);
    }

    private Object safeRedisGet(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            // Redis cluster down/slow — degrade to DB instead of failing the request
            log.warn("Redis GET failed for {} (fail-open to loader): {}", key, e.getMessage());
            return null;
        }
    }

    private void safeRedisPut(String key, Object value, long ttlSeconds) {
        try {
            redis.opsForValue().set(key, value, Duration.ofSeconds(ttlSeconds));
        } catch (RuntimeException e) {
            log.warn("Redis SET failed for {} (continuing with L1 only): {}", key, e.getMessage());
        }
    }

    /** base TTL plus uniform random jitter to spread expirations. */
    private long randomizedTtl() {
        long jitter = jitterSeconds <= 0 ? 0 : ThreadLocalRandom.current().nextLong(jitterSeconds + 1);
        return baseTtlSeconds + jitter;
    }
}
