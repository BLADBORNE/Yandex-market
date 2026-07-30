package ru.yandex.payment_service.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

@Validated
@ConfigurationProperties(prefix = "payment.balance")
public record PaymentBalanceProperties(
    @NotNull
    @DecimalMin(value = "0.00")
    @Digits(integer = 16, fraction = 2)
    BigDecimal initial
) {
}
