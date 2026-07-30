package ru.yandex.market_app.cache;

public class ProductCacheAccessException extends RuntimeException {

    public ProductCacheAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProductCacheAccessException(String message) {
        super(message);
    }
}
