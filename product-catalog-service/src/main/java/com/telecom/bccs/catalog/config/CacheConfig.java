package com.telecom.bccs.catalog.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * L1 in-memory cache (Caffeine). Very short TTL and bounded size: it absorbs the hottest
 * keys for sub-millisecond reads and shields Redis from request stampedes, while staying
 * fresh enough that a Kafka invalidation event closes the staleness window quickly.
 */
@Configuration
public class CacheConfig {

    public static final String L1_PRODUCTS = "l1Products";
    public static final String L1_OFFERS = "l1Offers";

    @Bean
    public CacheManager caffeineCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(L1_PRODUCTS, L1_OFFERS);
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfterWrite(Duration.ofSeconds(30))   // short — L2 is the source of truth
                .recordStats());                            // exposed to Micrometer/Prometheus
        // Allow caching of null markers handled at the service layer (penetration guard)
        manager.setAllowNullValues(true);
        return manager;
    }
}
