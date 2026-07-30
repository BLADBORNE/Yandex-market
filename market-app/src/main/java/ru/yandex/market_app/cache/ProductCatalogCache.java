package ru.yandex.market_app.cache;

import reactor.core.publisher.Mono;

public interface ProductCatalogCache {

    Mono<CachedProductCatalog> get();

    Mono<Void> put(CachedProductCatalog catalog);

    Mono<Void> evict();
}
