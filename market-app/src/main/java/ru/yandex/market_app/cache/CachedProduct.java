package ru.yandex.market_app.cache;

import ru.yandex.market_app.model.Product;

import java.math.BigDecimal;

public record CachedProduct(
    Long id,
    String imgPath,
    String title,
    String description,
    BigDecimal price
) {

    public static CachedProduct from(Product product) {
        return new CachedProduct(
            product.getId(),
            product.getImgPath(),
            product.getTitle(),
            product.getDescription(),
            product.getPrice()
        );
    }
}
