package ru.yandex.market_app.payment;

public class PaymentRejectedException extends RuntimeException {

    public PaymentRejectedException(String message) {
        super(message);
    }
}
