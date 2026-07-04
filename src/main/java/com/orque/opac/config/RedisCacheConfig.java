package com.orque.opac.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-cache TTLs for read-heavy, infrequently-changing lookups. Only data whose
 * staleness for a few minutes is harmless gets cached here — anything that must
 * reflect real-time state (user accounts, sessions, login attempts) stays uncached.
 */
@Configuration
@EnableCaching
public class RedisCacheConfig {

    public static final String CACHE_TENANT_SCOPE   = "tenantScope";
    public static final String CACHE_LICENSE_FLAGS   = "licenseFlags";
    public static final String CACHE_USER_TENANT      = "userTenant";

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .polymorphicTypeValidator(BasicPolymorphicTypeValidator.builder()
                        .allowIfBaseType(Object.class)
                        .build())
                .activateDefaultTyping(BasicPolymorphicTypeValidator.builder()
                                .allowIfBaseType(Object.class)
                                .build(),
                        ObjectMapper.DefaultTyping.EVERYTHING)
                .build();
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(mapper);

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(15))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer))
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> perCacheTtl = new HashMap<>();
        // Tenant master data — essentially static, invalidated on tenant create/update.
        perCacheTtl.put(CACHE_TENANT_SCOPE, defaultConfig.entryTtl(Duration.ofMinutes(60)));
        // License config in tenant settings JSON — changes only on activation/renewal.
        perCacheTtl.put(CACHE_LICENSE_FLAGS, defaultConfig.entryTtl(Duration.ofMinutes(10)));
        // Username → tenant UUID mapping — stable unless a user is reassigned.
        perCacheTtl.put(CACHE_USER_TENANT, defaultConfig.entryTtl(Duration.ofMinutes(60)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(perCacheTtl)
                .build();
    }
}
