package ru.yandex.payment_service.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentResult(
    UUID requestId,
    BigDecimal amount,
    BigDecimal remainingBalance
) {
}
