package com.telecom.bccs.catalog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * L2 distributed cache (Redis). Uses {@link GenericJackson2JsonRedisSerializer} with its
 * default constructor so polymorphic type information ({@code @class}) is embedded in the
 * JSON; this lets records like ProductDto/OfferDto be deserialized back to their exact type
 * (otherwise they would come back as LinkedHashMap and the cast would fail).
 *
 * Random-TTL and null-value handling live in
 * {@link com.telecom.bccs.catalog.service.TwoLevelCacheService}, which uses this template.
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory cf) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(cf);

        var keySerializer = new StringRedisSerializer();
        var valueSerializer = new GenericJackson2JsonRedisSerializer();

        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
