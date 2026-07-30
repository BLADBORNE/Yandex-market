package ru.yandex.market_app.payment;

public class PaymentServiceUnavailableException extends RuntimeException {

    public PaymentServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public PaymentServiceUnavailableException(String message) {
        super(message);
    }
}
