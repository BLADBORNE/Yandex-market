package ru.yandex.payment_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Signal;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import ru.yandex.payment_service.config.PaymentBalanceProperties;
import ru.yandex.payment_service.domain.PaymentResult;
import ru.yandex.payment_service.exception.IdempotencyConflictException;
import ru.yandex.payment_service.exception.InsufficientFundsException;
import ru.yandex.payment_service.exception.InvalidPaymentRequestException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentBalanceStoreTest {

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("10.00");
    private static final UUID CUSTOMER_ID = UUID.fromString(
        "10000000-0000-0000-0000-000000000001"
    );
    private static final UUID OTHER_CUSTOMER_ID = UUID.fromString(
        "10000000-0000-0000-0000-000000000002"
    );

    private PaymentBalanceStore store;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        store = new PaymentBalanceStore(new PaymentBalanceProperties(INITIAL_BALANCE));
        paymentService = new PaymentService(store);
    }

    @Test
    void shouldReturnConfiguredBalance() {
        StepVerifier.create(paymentService.getBalance(CUSTOMER_ID))
            .assertNext(balance -> assertMoneyEquals(INITIAL_BALANCE, balance))
            .verifyComplete();
    }

    @Test
    void shouldDebitExactBalance() {
        UUID requestId = UUID.randomUUID();

        StepVerifier.create(paymentService.makePayment(CUSTOMER_ID, requestId, INITIAL_BALANCE))
            .assertNext(result -> {
                assertEquals(requestId, result.requestId());
                assertMoneyEquals(INITIAL_BALANCE, result.amount());
                assertMoneyEquals(BigDecimal.ZERO, result.remainingBalance());
            })
            .verifyComplete();

        assertMoneyEquals(BigDecimal.ZERO, store.getBalance(CUSTOMER_ID));
    }

    @Test
    void shouldNotDebitWhenFundsAreInsufficient() {
        StepVerifier.create(paymentService.makePayment(
                CUSTOMER_ID,
                UUID.randomUUID(),
                new BigDecimal("10.01")
            ))
            .expectErrorSatisfies(error -> {
                assertTrue(error instanceof InsufficientFundsException);
                var exception = (InsufficientFundsException) error;
                assertMoneyEquals(INITIAL_BALANCE, exception.getAvailableBalance());
                assertMoneyEquals(new BigDecimal("10.01"), exception.getRequiredAmount());
            })
            .verify();

        assertMoneyEquals(INITIAL_BALANCE, store.getBalance(CUSTOMER_ID));
    }

    @Test
    void shouldExecuteDebitOnlyAfterSubscription() {
        var payment = paymentService.makePayment(
            CUSTOMER_ID,
            UUID.randomUUID(),
            BigDecimal.ONE
        );

        assertMoneyEquals(INITIAL_BALANCE, store.getBalance(CUSTOMER_ID));

        StepVerifier.create(payment)
            .expectNextCount(1)
            .verifyComplete();

        assertMoneyEquals(new BigDecimal("9.00"), store.getBalance(CUSTOMER_ID));
    }

    @Test
    void shouldRejectSubCentAmountWithoutChangingBalance() {
        StepVerifier.create(paymentService.makePayment(
                CUSTOMER_ID,
                UUID.randomUUID(),
                new BigDecimal("1.001")
            ))
            .expectError(InvalidPaymentRequestException.class)
            .verify();

        assertMoneyEquals(INITIAL_BALANCE, store.getBalance(CUSTOMER_ID));
    }

    @Test
    void shouldReturnStoredResultForRepeatedRequest() {
        UUID requestId = UUID.randomUUID();

        PaymentResult first = store.debit(CUSTOMER_ID, requestId, new BigDecimal("3.00"));
        PaymentResult repeated = store.debit(CUSTOMER_ID, requestId, new BigDecimal("3.0"));

        assertEquals(first, repeated);
        assertMoneyEquals(new BigDecimal("7.00"), store.getBalance(CUSTOMER_ID));
    }

    @Test
    void shouldRejectIdempotencyKeyReusedWithAnotherAmount() {
        UUID requestId = UUID.randomUUID();
        store.debit(CUSTOMER_ID, requestId, new BigDecimal("3.00"));

        assertThrows(
            IdempotencyConflictException.class,
            () -> store.debit(CUSTOMER_ID, requestId, new BigDecimal("4.00"))
        );
        assertMoneyEquals(new BigDecimal("7.00"), store.getBalance(CUSTOMER_ID));
    }

    @Test
    void shouldNeverOverdrawBalanceUnderConcurrentPayments() {
        var payments = Flux.range(0, 50)
            .flatMap(index -> paymentService
                .makePayment(CUSTOMER_ID, UUID.randomUUID(), BigDecimal.ONE)
                .subscribeOn(Schedulers.parallel())
                .materialize(), 50)
            .collectList();

        StepVerifier.create(payments)
            .assertNext(signals -> {
                long successfulPayments = signals.stream()
                    .filter(Signal::hasValue)
                    .count();
                long declinedPayments = signals.stream()
                    .filter(signal -> signal.isOnError()
                        && signal.getThrowable() instanceof InsufficientFundsException)
                    .count();

                assertEquals(10, successfulPayments);
                assertEquals(40, declinedPayments);
                assertMoneyEquals(BigDecimal.ZERO, store.getBalance(CUSTOMER_ID));
            })
            .verifyComplete();
    }

    @Test
    void shouldDebitOnlyOnceForConcurrentRepeatedRequest() {
        UUID requestId = UUID.randomUUID();
        var payments = Flux.range(0, 50)
            .flatMap(index -> paymentService
                .makePayment(CUSTOMER_ID, requestId, new BigDecimal("3.00"))
                .subscribeOn(Schedulers.parallel()), 50)
            .collectList();

        StepVerifier.create(payments)
            .assertNext(results -> {
                assertEquals(50, results.size());
                assertTrue(results.stream().allMatch(
                    result -> result.requestId().equals(requestId)
                        && result.remainingBalance().compareTo(new BigDecimal("7.00")) == 0
                ));
                assertMoneyEquals(new BigDecimal("7.00"), store.getBalance(CUSTOMER_ID));
            })
            .verifyComplete();
    }

    @Test
    void shouldKeepBalancesIndependentForDifferentCustomers() {
        UUID sharedRequestId = UUID.randomUUID();

        PaymentResult firstCustomer = store.debit(
            CUSTOMER_ID,
            sharedRequestId,
            new BigDecimal("3.00")
        );
        PaymentResult secondCustomer = store.debit(
            OTHER_CUSTOMER_ID,
            sharedRequestId,
            new BigDecimal("4.00")
        );

        assertMoneyEquals(new BigDecimal("7.00"), firstCustomer.remainingBalance());
        assertMoneyEquals(new BigDecimal("6.00"), secondCustomer.remainingBalance());
        assertMoneyEquals(new BigDecimal("7.00"), store.getBalance(CUSTOMER_ID));
        assertMoneyEquals(new BigDecimal("6.00"), store.getBalance(OTHER_CUSTOMER_ID));
    }

    @Test
    void shouldRejectMissingCustomerIdentifier() {
        StepVerifier.create(paymentService.getBalance(null))
            .expectError(InvalidPaymentRequestException.class)
            .verify();

        StepVerifier.create(paymentService.makePayment(
                null,
                UUID.randomUUID(),
                BigDecimal.ONE
            ))
            .expectError(InvalidPaymentRequestException.class)
            .verify();
    }

    private void assertMoneyEquals(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual));
    }
}
