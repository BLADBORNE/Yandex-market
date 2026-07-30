package ru.yandex.market_app.exception;

public final class OperationNotSupportedException extends RuntimeException {

    public OperationNotSupportedException(String message) {
        super(message);
    }
}
