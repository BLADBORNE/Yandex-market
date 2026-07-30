package ru.yandex.payment_service.service;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.yandex.payment_service.domain.PaymentResult;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public final class PaymentService {

    private final PaymentBalanceStore paymentBalanceStore;

    public PaymentService(PaymentBalanceStore paymentBalanceStore) {
        this.paymentBalanceStore = paymentBalanceStore;
    }

    public Mono<BigDecimal> getBalance(UUID customerId) {
        return Mono.fromSupplier(() -> paymentBalanceStore.getBalance(customerId));
    }

    public Mono<PaymentResult> makePayment(
        UUID customerId,
        UUID requestId,
        BigDecimal amount
    ) {
        return Mono.fromSupplier(() -> paymentBalanceStore.debit(customerId, requestId, amount));
    }
}
