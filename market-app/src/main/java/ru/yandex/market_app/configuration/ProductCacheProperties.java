package ru.yandex.market_app.configuration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "market.cache.products")
public record ProductCacheProperties(
    @NotBlank String key,
    @NotNull Duration ttl
) {

    @AssertTrue(message = "market.cache.products.ttl должен быть больше нуля")
    public boolean isTtlPositive() {
        return ttl != null && !ttl.isZero() && !ttl.isNegative();
    }
}
