package ru.yandex.payment_service.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "payment.balance.initial=1000000.00"
)
@AutoConfigureWebTestClient
class PaymentApiIntegrationTest {

    private static final UUID CUSTOMER_ID = UUID.fromString(
        "20000000-0000-0000-0000-000000000001"
    );

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private PaymentBalanceStore paymentBalanceStore;

    @Test
    void shouldReturnCurrentBalanceAsJson() {
        BigDecimal expectedBalance = paymentBalanceStore.getBalance(CUSTOMER_ID);

        authorizedClient("payment.read").get()
            .uri("/api/v1/balance")
            .header("X-Customer-Id", CUSTOMER_ID.toString())
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
        BigDecimal expectedBalance = paymentBalanceStore.getBalance(CUSTOMER_ID).subtract(amount);

        authorizedClient("payment.write").post()
            .uri("/api/v1/payment")
            .header("X-Customer-Id", CUSTOMER_ID.toString())
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

        assertMoneyEquals(expectedBalance, paymentBalanceStore.getBalance(CUSTOMER_ID));
    }

    @Test
    void shouldReturnConflictWithoutDebitingForInsufficientFunds() {
        BigDecimal availableBalance = paymentBalanceStore.getBalance(CUSTOMER_ID);
        BigDecimal requiredAmount = availableBalance.add(BigDecimal.ONE);

        authorizedClient("payment.write").post()
            .uri("/api/v1/payment")
            .header("X-Customer-Id", CUSTOMER_ID.toString())
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

        assertMoneyEquals(availableBalance, paymentBalanceStore.getBalance(CUSTOMER_ID));
    }

    @Test
    void shouldReturnConflictWhenRequestIdIsReusedWithAnotherAmount() {
        UUID requestId = UUID.randomUUID();
        BigDecimal balanceBeforePayment = paymentBalanceStore.getBalance(CUSTOMER_ID);

        authorizedClient("payment.write").post()
            .uri("/api/v1/payment")
            .header("X-Customer-Id", CUSTOMER_ID.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new PaymentRequest(requestId, BigDecimal.ONE))
            .exchange()
            .expectStatus().isOk();

        authorizedClient("payment.write").post()
            .uri("/api/v1/payment")
            .header("X-Customer-Id", CUSTOMER_ID.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new PaymentRequest(requestId, BigDecimal.TWO))
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody(ErrorResponse.class)
            .value(response -> assertEquals("IDEMPOTENCY_CONFLICT", response.getCode()));

        assertMoneyEquals(
            balanceBeforePayment.subtract(BigDecimal.ONE),
            paymentBalanceStore.getBalance(CUSTOMER_ID)
        );
    }

    @Test
    void shouldReturnBadRequestForInvalidBean() {
        authorizedClient("payment.write").post()
            .uri("/api/v1/payment")
            .header("X-Customer-Id", CUSTOMER_ID.toString())
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
        BigDecimal balanceBeforePayment = paymentBalanceStore.getBalance(CUSTOMER_ID);

        authorizedClient("payment.write").post()
            .uri("/api/v1/payment")
            .header("X-Customer-Id", CUSTOMER_ID.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new PaymentRequest(UUID.randomUUID(), new BigDecimal("1.001")))
            .exchange()
            .expectStatus().isBadRequest()
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody(ErrorResponse.class)
            .value(response -> assertEquals("INVALID_REQUEST", response.getCode()));

        assertMoneyEquals(balanceBeforePayment, paymentBalanceStore.getBalance(CUSTOMER_ID));
    }

    @Test
    void shouldReturnBadRequestForMalformedJson() {
        authorizedClient("payment.write").post()
            .uri("/api/v1/payment")
            .header("X-Customer-Id", CUSTOMER_ID.toString())
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
        BigDecimal balanceBeforePayment = paymentBalanceStore.getBalance(CUSTOMER_ID);

        authorizedClient("payment.write").post()
            .uri("/api/v1/payment")
            .header("X-Customer-Id", CUSTOMER_ID.toString())
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

        assertMoneyEquals(balanceBeforePayment, paymentBalanceStore.getBalance(CUSTOMER_ID));
    }

    @Test
    void shouldRejectRequestWithoutAccessToken() {
        webTestClient.get()
            .uri("/api/v1/balance")
            .header("X-Customer-Id", CUSTOMER_ID.toString())
            .exchange()
            .expectStatus().isUnauthorized()
            .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody(ErrorResponse.class)
            .value(response -> assertEquals("UNAUTHORIZED", response.getCode()));
    }

    @Test
    void shouldRejectMalformedAccessTokenWithBearerChallenge() {
        webTestClient.get()
            .uri("/api/v1/balance")
            .headers(headers -> headers.setBearerAuth("not-a-jwt"))
            .header("X-Customer-Id", CUSTOMER_ID.toString())
            .exchange()
            .expectStatus().isUnauthorized()
            .expectHeader().valueEquals(
                HttpHeaders.WWW_AUTHENTICATE,
                "Bearer error=\"invalid_token\""
            )
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody(ErrorResponse.class)
            .value(response -> assertEquals("UNAUTHORIZED", response.getCode()));
    }

    @Test
    void shouldRejectTokenWithoutRequiredScope() {
        authorizedClient("payment.read").post()
            .uri("/api/v1/payment")
            .header("X-Customer-Id", CUSTOMER_ID.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new PaymentRequest(UUID.randomUUID(), BigDecimal.ONE))
            .exchange()
            .expectStatus().isForbidden()
            .expectHeader().valueEquals(
                HttpHeaders.WWW_AUTHENTICATE,
                "Bearer error=\"insufficient_scope\""
            )
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody(ErrorResponse.class)
            .value(response -> assertEquals("FORBIDDEN", response.getCode()));
    }

    @Test
    void shouldRejectMissingCustomerHeader() {
        authorizedClient("payment.read").get()
            .uri("/api/v1/balance")
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void shouldRejectMalformedCustomerHeader() {
        authorizedClient("payment.read").get()
            .uri("/api/v1/balance")
            .header("X-Customer-Id", "not-a-uuid")
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void shouldKeepApiBalancesAndIdempotencyIndependentPerCustomer() {
        UUID otherCustomerId = UUID.fromString("20000000-0000-0000-0000-000000000002");
        UUID sharedRequestId = UUID.randomUUID();
        BigDecimal firstBalance = paymentBalanceStore.getBalance(CUSTOMER_ID);
        BigDecimal secondBalance = paymentBalanceStore.getBalance(otherCustomerId);

        authorizedClient("payment.write").post()
            .uri("/api/v1/payment")
            .header("X-Customer-Id", CUSTOMER_ID.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new PaymentRequest(sharedRequestId, BigDecimal.ONE))
            .exchange()
            .expectStatus().isOk();

        authorizedClient("payment.write").post()
            .uri("/api/v1/payment")
            .header("X-Customer-Id", otherCustomerId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new PaymentRequest(sharedRequestId, BigDecimal.TWO))
            .exchange()
            .expectStatus().isOk();

        assertMoneyEquals(
            firstBalance.subtract(BigDecimal.ONE),
            paymentBalanceStore.getBalance(CUSTOMER_ID)
        );
        assertMoneyEquals(
            secondBalance.subtract(BigDecimal.TWO),
            paymentBalanceStore.getBalance(otherCustomerId)
        );
    }

    @Test
    void shouldExposeHealthWithoutAuthentication() {
        webTestClient.get()
            .uri("/actuator/health")
            .exchange()
            .expectStatus().isOk();
    }

    private WebTestClient authorizedClient(String scope) {
        return webTestClient.mutateWith(mockJwt().authorities(
            new SimpleGrantedAuthority("SCOPE_" + scope)
        ));
    }

    private void assertMoneyEquals(BigDecimal expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, expected.compareTo(actual));
    }
}
