package ru.yandex.payment_service.exception;

import java.math.BigDecimal;

public final class InsufficientFundsException extends RuntimeException {

    private final BigDecimal availableBalance;
    private final BigDecimal requiredAmount;

    public InsufficientFundsException(BigDecimal availableBalance, BigDecimal requiredAmount) {
        super("Недостаточно средств для осуществления платежа");
        this.availableBalance = availableBalance;
        this.requiredAmount = requiredAmount;
    }

    public BigDecimal getAvailableBalance() {
        return availableBalance;
    }

    public BigDecimal getRequiredAmount() {
        return requiredAmount;
    }
}
