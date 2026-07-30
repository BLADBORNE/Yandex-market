package ru.yandex.market_app.payment;

import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class InsufficientFundsException extends RuntimeException {

    private final BigDecimal availableBalance;
    private final BigDecimal requiredAmount;

    public InsufficientFundsException(BigDecimal availableBalance, BigDecimal requiredAmount) {
        super("Недостаточно средств: доступно %s руб., требуется %s руб."
            .formatted(availableBalance, requiredAmount));
        this.availableBalance = availableBalance;
        this.requiredAmount = requiredAmount;
    }
}
