package ru.yandex.payment_service.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import ru.yandex.payment_service.generated.model.BalanceResponse;
import ru.yandex.payment_service.generated.model.ErrorResponse;
import ru.yandex.payment_service.generated.model.PaymentRequest;
import ru.yandex.payment_service.generated.model.PaymentResponse;
import ru.yandex.payment_service.service.PaymentBalanceStore;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "payment.balance.initial=1000000.00"
)
@AutoConfigureWebTestClient
class PaymentApiIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private PaymentBalanceStore paymentBalanceStore;

    @Test
    void shouldReturnCurrentBalanceAsJson() {
        BigDecimal expectedBalance = paymentBalanceStore.getBalance();

        webTestClient.get()
            .uri("/api/v1/balance")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody(BalanceResponse.class)
            .value(response -> assertMoneyEquals(expectedBalance, response.getBalance()));
    }

    @Test
    void shouldCompletePaymentAndReturnRemainingBalance() {
        UUID requestId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("1.25");
        BigDecimal expectedBalance = paymentBalanceStore.getBalance().subtract(amount);

        webTestClient.post()
            .uri("/api/v1/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new PaymentRequest(requestId, amount))
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody(PaymentResponse.class)
            .value(response -> {
                assertEquals(requestId, response.getRequestId());
                assertMoneyEquals(amount, response.getAmount());
                assertMoneyEquals(expectedBalance, response.getRemainingBalance());
            });

        assertMoneyEquals(expectedBalance, paymentBalanceStore.getBalance());
    }

    @Test
    void shouldReturnConflictWithoutDebitingForInsufficientFunds() {
        BigDecimal availableBalance = paymentBalanceStore.getBalance();
        BigDecimal requiredAmount = availableBalance.add(BigDecimal.ONE);

        webTestClient.post()
            .uri("/api/v1/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new PaymentRequest(UUID.randomUUID(), requiredAmount))
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody(ErrorResponse.class)
            .value(response -> {
                assertEquals("INSUFFICIENT_FUNDS", response.getCode());
                assertNotNull(response.getMessage());
                assertMoneyEquals(availableBalance, response.getAvailableBalance());
                assertMoneyEquals(requiredAmount, response.getRequiredAmount());
            });

        assertMoneyEquals(availableBalance, paymentBalanceStore.getBalance());
    }

    @Test
    void shouldReturnConflictWhenRequestIdIsReusedWithAnotherAmount() {
        UUID requestId = UUID.randomUUID();
        BigDecimal balanceBeforePayment = paymentBalanceStore.getBalance();

        webTestClient.post()
            .uri("/api/v1/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new PaymentRequest(requestId, BigDecimal.ONE))
            .exchange()
            .expectStatus().isOk();

        webTestClient.post()
            .uri("/api/v1/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new PaymentRequest(requestId, BigDecimal.TWO))
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody(ErrorResponse.class)
            .value(response -> assertEquals("IDEMPOTENCY_CONFLICT", response.getCode()));

        assertMoneyEquals(
            balanceBeforePayment.subtract(BigDecimal.ONE),
            paymentBalanceStore.getBalance()
        );
    }

    @Test
    void shouldReturnBadRequestForInvalidBean() {
        webTestClient.post()
            .uri("/api/v1/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("amount", BigDecimal.ONE))
            .exchange()
            .expectStatus().isBadRequest()
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody(ErrorResponse.class)
            .value(response -> assertEquals("INVALID_REQUEST", response.getCode()));
    }

    @Test
    void shouldReturnBadRequestForSubCentAmount() {
        BigDecimal balanceBeforePayment = paymentBalanceStore.getBalance();

        webTestClient.post()
            .uri("/api/v1/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new PaymentRequest(UUID.randomUUID(), new BigDecimal("1.001")))
            .exchange()
            .expectStatus().isBadRequest()
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody(ErrorResponse.class)
            .value(response -> assertEquals("INVALID_REQUEST", response.getCode()));

        assertMoneyEquals(balanceBeforePayment, paymentBalanceStore.getBalance());
    }

    @Test
    void shouldReturnBadRequestForMalformedJson() {
        webTestClient.post()
            .uri("/api/v1/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{not-json}")
            .exchange()
            .expectStatus().isBadRequest()
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody(ErrorResponse.class)
            .value(response -> assertEquals("INVALID_REQUEST", response.getCode()));
    }

    @Test
    void shouldRejectUnknownJsonProperties() {
        BigDecimal balanceBeforePayment = paymentBalanceStore.getBalance();

        webTestClient.post()
            .uri("/api/v1/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {
                  "requestId": "%s",
                  "amount": 1.00,
                  "unexpected": true
                }
                """.formatted(UUID.randomUUID()))
            .exchange()
            .expectStatus().isBadRequest()
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody(ErrorResponse.class)
            .value(response -> assertEquals("INVALID_REQUEST", response.getCode()));

        assertMoneyEquals(balanceBeforePayment, paymentBalanceStore.getBalance());
    }

    private void assertMoneyEquals(BigDecimal expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, expected.compareTo(actual));
    }
}
