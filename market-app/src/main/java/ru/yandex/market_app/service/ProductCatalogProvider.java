package ru.yandex.market_app.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.cache.CachedProduct;
import ru.yandex.market_app.cache.CachedProductCatalog;
import ru.yandex.market_app.cache.ProductCacheAccessException;
import ru.yandex.market_app.cache.ProductCatalogCache;
import ru.yandex.market_app.repository.ProductRepository;

import java.util.Comparator;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductCatalogProvider {

    private final ProductCatalogCache productCatalogCache;
    private final ProductRepository productRepository;

    public Mono<CachedProductCatalog> getCatalog() {
        return productCatalogCache.get()
            .doOnNext(catalog -> log.debug("Каталог товаров загружен из Redis"))
            .switchIfEmpty(Mono.defer(this::refresh))
            .onErrorResume(ProductCacheAccessException.class, error -> {
                log.warn("Redis недоступен при чтении каталога, используем PostgreSQL: {}", error.getMessage());
                return loadFromDatabase(false);
            });
    }

    public Mono<CachedProduct> getProduct(Long id) {
        return getCatalog()
            .flatMap(catalog -> Mono.justOrEmpty(catalog.findById(id))
                .switchIfEmpty(Mono.defer(() -> productRepository.findById(id)
                    .map(CachedProduct::from)
                    .flatMap(product -> refresh().thenReturn(product)))));
    }

    public Mono<CachedProductCatalog> getCatalogContaining(Set<Long> productIds) {
        return getCatalog()
            .flatMap(catalog -> containsAll(catalog, productIds)
                ? Mono.just(catalog)
                : refresh());
    }

    public Mono<CachedProductCatalog> refresh() {
        return loadFromDatabase(true);
    }

    private Mono<CachedProductCatalog> loadFromDatabase(boolean updateCache) {
        return productRepository.findAll()
            .map(CachedProduct::from)
            .sort(Comparator.comparing(CachedProduct::id))
            .collectList()
            .map(CachedProductCatalog::new)
            .flatMap(catalog -> updateCache
                ? productCatalogCache.put(catalog)
                .doOnSuccess(ignored -> log.debug("Каталог товаров записан в Redis"))
                .onErrorResume(ProductCacheAccessException.class, error -> {
                    log.warn("Не удалось обновить Redis, возвращаем данные PostgreSQL: {}", error.getMessage());
                    return Mono.empty();
                })
                .thenReturn(catalog)
                : Mono.just(catalog));
    }

    public Mono<Void> evict() {
        return productCatalogCache.evict()
            .onErrorResume(ProductCacheAccessException.class, error -> {
                log.warn("Не удалось удалить кеш товаров: {}", error.getMessage());
                return Mono.empty();
            });
    }

    private boolean containsAll(CachedProductCatalog catalog, Set<Long> productIds) {
        if (productIds.isEmpty()) {
            return true;
        }

        var cachedIds = catalog.products().stream()
            .map(CachedProduct::id)
            .collect(java.util.stream.Collectors.toSet());
        return cachedIds.containsAll(productIds);
    }
}
