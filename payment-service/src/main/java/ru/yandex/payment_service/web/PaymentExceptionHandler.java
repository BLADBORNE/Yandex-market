package ru.yandex.payment_service.web;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;
import ru.yandex.payment_service.exception.IdempotencyConflictException;
import ru.yandex.payment_service.exception.InsufficientFundsException;
import ru.yandex.payment_service.exception.InvalidPaymentRequestException;
import ru.yandex.payment_service.generated.model.ErrorResponse;

import java.util.stream.Collectors;

@RestControllerAdvice(basePackages = "ru.yandex.payment_service.generated.api")
public final class PaymentExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PaymentExceptionHandler.class);

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientFunds(InsufficientFundsException exception) {
        var response = new ErrorResponse("INSUFFICIENT_FUNDS", exception.getMessage())
            .availableBalance(exception.getAvailableBalance())
            .requiredAmount(exception.getRequiredAmount());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(
        IdempotencyConflictException exception
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ErrorResponse("IDEMPOTENCY_CONFLICT", exception.getMessage()));
    }

    @ExceptionHandler(InvalidPaymentRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPaymentRequest(
        InvalidPaymentRequestException exception
    ) {
        return ResponseEntity.badRequest()
            .body(new ErrorResponse("INVALID_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ErrorResponse> handleBindingError(WebExchangeBindException exception) {
        String message = exception.getFieldErrors().stream()
            .map(error -> "%s: %s".formatted(error.getField(), error.getDefaultMessage()))
            .collect(Collectors.joining("; "));

        if (message.isBlank()) {
            message = "Некорректные параметры запроса";
        }

        return ResponseEntity.badRequest()
            .body(new ErrorResponse("INVALID_REQUEST", message));
    }

    @ExceptionHandler({ConstraintViolationException.class, ServerWebInputException.class})
    public ResponseEntity<ErrorResponse> handleInvalidInput(Exception exception) {
        return ResponseEntity.badRequest()
            .body(new ErrorResponse("INVALID_REQUEST", "Некорректный формат запроса"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedError(Exception exception) {
        LOGGER.error("Unexpected payment service error", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse(
                "INTERNAL_ERROR",
                "Внутренняя ошибка сервиса платежей"
            ));
    }
}
