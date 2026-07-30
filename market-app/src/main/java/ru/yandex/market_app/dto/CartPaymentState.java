package ru.yandex.market_app.dto;

import java.math.BigDecimal;

public record CartPaymentState(
    CheckoutStatus status,
    boolean canBuy,
    BigDecimal balance,
    String message
) {

    public static CartPaymentState empty() {
        return new CartPaymentState(CheckoutStatus.EMPTY, false, null, null);
    }

    public static CartPaymentState available(BigDecimal balance) {
        return new CartPaymentState(
            CheckoutStatus.AVAILABLE,
            true,
            balance,
            "На счёте достаточно средств. Доступно: %s руб.".formatted(balance)
        );
    }

    public static CartPaymentState insufficient(BigDecimal balance, BigDecimal total) {
        return new CartPaymentState(
            CheckoutStatus.INSUFFICIENT_FUNDS,
            false,
            balance,
            "Недостаточно средств: доступно %s руб., требуется %s руб.".formatted(balance, total)
        );
    }

    public static CartPaymentState insufficientAfterPaymentAttempt() {
        return new CartPaymentState(
            CheckoutStatus.INSUFFICIENT_FUNDS,
            false,
            null,
            "Оплата не выполнена: на счёте недостаточно средств."
        );
    }

    public static CartPaymentState unavailable() {
        return new CartPaymentState(
            CheckoutStatus.SERVICE_UNAVAILABLE,
            false,
            null,
            "Сервис платежей недоступен. Оформление заказа временно невозможно."
        );
    }
}
