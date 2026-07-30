package ru.yandex.market_app.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.configuration.PaymentClientProperties;
import ru.yandex.market_app.payment.generated.api.PaymentsApi;
import ru.yandex.market_app.payment.generated.model.BalanceResponse;
import ru.yandex.market_app.payment.generated.model.ErrorResponse;
import ru.yandex.market_app.payment.generated.model.PaymentRequest;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class GeneratedPaymentGateway implements PaymentGateway {

    private static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    private static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    private static final Set<String> DEFINITIVE_OAUTH_ERRORS = Set.of(
        "invalid_client",
        "unauthorized_client",
        "invalid_scope",
        "unsupported_grant_type"
    );

    private final PaymentsApi paymentsApi;
    private final PaymentClientProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<BigDecimal> getBalance(UUID customerId) {
        return paymentsApi.getBalance(customerId)
            .timeout(properties.responseTimeout())
            .switchIfEmpty(Mono.error(new PaymentServiceUnavailableException(
                "Сервис платежей вернул пустой ответ"
            )))
            .map(this::validateBalance)
            .onErrorMap(this::mapBalanceError);
    }

    @Override
    public Mono<PaymentReceipt> pay(UUID customerId, UUID requestId, BigDecimal amount) {
        var request = new PaymentRequest()
            .requestId(requestId)
            .amount(amount);

        return paymentsApi.makePayment(customerId, request)
            .timeout(properties.responseTimeout())
            .switchIfEmpty(Mono.error(new PaymentServiceUnavailableException(
                "Сервис платежей вернул пустой ответ"
            )))
            .map(response -> validatePaymentResponse(
                requestId,
                amount,
                response.getRequestId(),
                response.getAmount(),
                response.getRemainingBalance()
            ))
            .onErrorMap(error -> mapPaymentError(error, amount));
    }

    private BigDecimal validateBalance(BalanceResponse response) {
        BigDecimal balance = response.getBalance();
        if (balance == null || balance.signum() < 0) {
            throw new PaymentServiceUnavailableException(
                "Сервис платежей вернул некорректный баланс"
            );
        }
        return balance;
    }

    private PaymentReceipt validatePaymentResponse(
        UUID expectedRequestId,
        BigDecimal expectedAmount,
        UUID actualRequestId,
        BigDecimal actualAmount,
        BigDecimal remainingBalance
    ) {
        if (!expectedRequestId.equals(actualRequestId)
            || actualAmount == null
            || expectedAmount.compareTo(actualAmount) != 0
            || remainingBalance == null
            || remainingBalance.signum() < 0) {
            throw new PaymentServiceUnavailableException(
                "Сервис платежей вернул некорректный ответ"
            );
        }

        return new PaymentReceipt(actualRequestId, actualAmount, remainingBalance);
    }

    private Throwable mapBalanceError(Throwable error) {
        if (error instanceof PaymentServiceUnavailableException) {
            return error;
        }

        return new PaymentServiceUnavailableException("Сервис платежей недоступен", error);
    }

    private Throwable mapPaymentError(Throwable error, BigDecimal requestedAmount) {
        if (error instanceof PaymentServiceUnavailableException
            || error instanceof InsufficientFundsException
            || error instanceof PaymentRejectedException) {
            return error;
        }

        if (error instanceof OAuth2AuthorizationException authorizationException
            && DEFINITIVE_OAUTH_ERRORS.contains(
                authorizationException.getError().getErrorCode()
            )) {
            return new PaymentRejectedException(
                "OAuth2-сервер отклонил авторизацию payment-service"
            );
        }

        if (error instanceof WebClientResponseException responseException) {
            ErrorResponse response = readErrorResponse(responseException);
            if (responseException.getStatusCode() == HttpStatus.UNAUTHORIZED
                || responseException.getStatusCode() == HttpStatus.FORBIDDEN) {
                return new PaymentRejectedException("Ошибка авторизации в сервисе платежей");
            }

            if (responseException.getStatusCode() == HttpStatus.CONFLICT
                && response != null
                && INSUFFICIENT_FUNDS.equals(response.getCode())) {
                BigDecimal available = response.getAvailableBalance() == null
                    ? BigDecimal.ZERO
                    : response.getAvailableBalance();
                BigDecimal required = response.getRequiredAmount() == null
                    ? requestedAmount
                    : response.getRequiredAmount();
                return new InsufficientFundsException(available, required);
            }

            if (responseException.getStatusCode() == HttpStatus.CONFLICT
                && response != null
                && IDEMPOTENCY_CONFLICT.equals(response.getCode())) {
                return new PaymentServiceUnavailableException(
                    "Платёж требует сверки по ключу идемпотентности",
                    responseException
                );
            }

            if (responseException.getStatusCode().is4xxClientError()) {
                String message = response == null
                    ? "Сервис платежей отклонил запрос"
                    : response.getMessage();
                return new PaymentRejectedException(message);
            }
        }

        return new PaymentServiceUnavailableException("Сервис платежей недоступен", error);
    }

    private ErrorResponse readErrorResponse(WebClientResponseException exception) {
        try {
            return objectMapper.readValue(exception.getResponseBodyAsByteArray(), ErrorResponse.class);
        } catch (Exception ignored) {
            return null;
        }
    }
}
