package ru.yandex.market_app.configuration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "market.payment-service")
public record PaymentClientProperties(
    @NotNull URI baseUrl,
    @NotNull Duration connectTimeout,
    @NotNull Duration responseTimeout
) {

    @AssertTrue(message = "Таймауты payment-service должны быть больше нуля")
    public boolean areTimeoutsPositive() {
        return connectTimeout != null
            && responseTimeout != null
            && !connectTimeout.isZero()
            && !connectTimeout.isNegative()
            && !responseTimeout.isZero()
            && !responseTimeout.isNegative();
    }
}
