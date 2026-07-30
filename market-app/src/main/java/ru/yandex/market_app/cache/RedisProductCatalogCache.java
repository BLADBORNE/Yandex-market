package ru.yandex.market_app.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.configuration.ProductCacheProperties;

@Repository
@RequiredArgsConstructor
public class RedisProductCatalogCache implements ProductCatalogCache {

    private final ReactiveRedisTemplate<String, CachedProductCatalog> redisTemplate;
    private final ProductCacheProperties properties;

    @Override
    public Mono<CachedProductCatalog> get() {
        return redisTemplate.opsForValue()
            .get(properties.key())
            .onErrorMap(error -> new ProductCacheAccessException("Не удалось прочитать кеш товаров", error));
    }

    @Override
    public Mono<Void> put(CachedProductCatalog catalog) {
        return redisTemplate.opsForValue()
            .set(properties.key(), catalog, properties.ttl())
            .flatMap(saved -> saved
                ? Mono.<Void>empty()
                : Mono.<Void>error(new ProductCacheAccessException("Redis не подтвердил запись кеша товаров")))
            .onErrorMap(
                error -> !(error instanceof ProductCacheAccessException),
                error -> new ProductCacheAccessException("Не удалось записать кеш товаров", error)
            );
    }

    @Override
    public Mono<Void> evict() {
        return redisTemplate.delete(properties.key())
            .then()
            .onErrorMap(error -> new ProductCacheAccessException("Не удалось удалить кеш товаров", error));
    }
}
