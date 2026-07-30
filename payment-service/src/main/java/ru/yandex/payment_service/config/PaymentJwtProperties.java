package ru.yandex.payment_service.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "payment.security.jwt")
public record PaymentJwtProperties(
    @NotBlank String authorizedClientId
) {
}
