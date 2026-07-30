package ru.yandex.market_app.payment;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentGateway {

    Mono<BigDecimal> getBalance();

    Mono<PaymentReceipt> pay(UUID requestId, BigDecimal amount);
}
