package ru.yandex.market_app.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.configuration.PaymentClientProperties;
import ru.yandex.market_app.payment.generated.api.PaymentsApi;
import ru.yandex.market_app.payment.generated.model.ErrorResponse;
import ru.yandex.market_app.payment.generated.model.PaymentRequest;

import java.math.BigDecimal;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class GeneratedPaymentGateway implements PaymentGateway {

    private static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";

    private final PaymentsApi paymentsApi;
    private final PaymentClientProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<BigDecimal> getBalance() {
        return paymentsApi.getBalance()
            .timeout(properties.responseTimeout())
            .switchIfEmpty(Mono.error(new PaymentServiceUnavailableException(
                "Сервис платежей вернул пустой ответ"
            )))
            .map(response -> validateBalance(response.getBalance()))
            .onErrorMap(this::mapBalanceError);
    }

    @Override
    public Mono<PaymentReceipt> pay(UUID requestId, BigDecimal amount) {
        var request = new PaymentRequest()
            .requestId(requestId)
            .amount(amount);

        return paymentsApi.makePayment(request)
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

    private BigDecimal validateBalance(BigDecimal balance) {
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

        if (error instanceof WebClientResponseException responseException) {
            ErrorResponse response = readErrorResponse(responseException);
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
