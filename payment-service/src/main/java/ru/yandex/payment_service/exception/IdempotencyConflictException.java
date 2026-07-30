package ru.yandex.payment_service.exception;

import java.util.UUID;

public final class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(UUID requestId) {
        super("Запрос с идентификатором %s уже был выполнен с другой суммой".formatted(requestId));
    }
}
