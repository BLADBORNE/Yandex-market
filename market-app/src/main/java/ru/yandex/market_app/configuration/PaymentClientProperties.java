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
    @NotNull Duration responseTimeout,
    @NotNull Duration retryDelay
) {

    @AssertTrue(message = "Таймауты и задержка повтора payment-service должны быть больше нуля")
    public boolean areDurationsPositive() {
        return connectTimeout != null
            && responseTimeout != null
            && retryDelay != null
            && !connectTimeout.isZero()
            && !connectTimeout.isNegative()
            && !responseTimeout.isZero()
            && !responseTimeout.isNegative()
            && !retryDelay.isZero()
            && !retryDelay.isNegative();
    }
}
