package ru.yandex.market_app.cache;

import java.util.List;
import java.util.Optional;

public record CachedProductCatalog(List<CachedProduct> products) {

    public CachedProductCatalog {
        products = List.copyOf(products);
    }

    public Optional<CachedProduct> findById(Long id) {
        return products.stream()
            .filter(product -> product.id().equals(id))
            .findFirst();
    }
}
