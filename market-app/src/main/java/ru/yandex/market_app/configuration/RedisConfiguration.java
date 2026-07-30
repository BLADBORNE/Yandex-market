package ru.yandex.market_app.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import ru.yandex.market_app.cache.CachedProductCatalog;

@Configuration(proxyBeanMethods = false)
public class RedisConfiguration {

    @Bean
    ReactiveRedisTemplate<String, CachedProductCatalog> productCatalogRedisTemplate(
        ReactiveRedisConnectionFactory connectionFactory,
        ObjectMapper objectMapper
    ) {
        var keySerializer = new StringRedisSerializer();
        var valueSerializer = new Jackson2JsonRedisSerializer<>(objectMapper, CachedProductCatalog.class);
        var serializationContext = RedisSerializationContext
            .<String, CachedProductCatalog>newSerializationContext(keySerializer)
            .value(valueSerializer)
            .build();

        return new ReactiveRedisTemplate<>(connectionFactory, serializationContext);
    }
}
