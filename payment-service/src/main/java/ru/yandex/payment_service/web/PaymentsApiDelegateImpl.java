package ru.yandex.payment_service.web;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.yandex.payment_service.exception.InvalidPaymentRequestException;
import ru.yandex.payment_service.generated.api.PaymentsApiDelegate;
import ru.yandex.payment_service.generated.model.BalanceResponse;
import ru.yandex.payment_service.generated.model.PaymentRequest;
import ru.yandex.payment_service.generated.model.PaymentResponse;
import ru.yandex.payment_service.service.PaymentService;

@Service
public final class PaymentsApiDelegateImpl implements PaymentsApiDelegate {

    private final PaymentService paymentService;

    public PaymentsApiDelegateImpl(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Override
    public Mono<ResponseEntity<BalanceResponse>> getBalance() {
        return paymentService.getBalance()
            .map(balance -> ResponseEntity.ok(new BalanceResponse(balance)));
    }

    @Override
    public Mono<ResponseEntity<PaymentResponse>> makePayment(Mono<PaymentRequest> paymentRequest) {
        return paymentRequest
            .switchIfEmpty(Mono.error(
                new InvalidPaymentRequestException("Тело запроса не указано")
            ))
            .flatMap(request -> paymentService.makePayment(
                request.getRequestId(),
                request.getAmount()
            ))
            .map(result -> ResponseEntity.ok(new PaymentResponse(
                result.requestId(),
                result.amount(),
                result.remainingBalance()
            )));
    }
}
