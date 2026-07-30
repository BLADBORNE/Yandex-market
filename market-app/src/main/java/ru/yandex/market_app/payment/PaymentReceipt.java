package ru.yandex.market_app.payment;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentReceipt(
    UUID requestId,
    BigDecimal amount,
    BigDecimal remainingBalance
) {
}
