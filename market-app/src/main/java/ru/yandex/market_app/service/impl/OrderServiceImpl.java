package ru.yandex.market_app.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.configuration.PaymentClientProperties;
import ru.yandex.market_app.dto.GetListOrderModelDto;
import ru.yandex.market_app.dto.GetOrderModelDto;
import ru.yandex.market_app.mapper.MarketMapper;
import ru.yandex.market_app.model.Order;
import ru.yandex.market_app.payment.InsufficientFundsException;
import ru.yandex.market_app.payment.PaymentGateway;
import ru.yandex.market_app.payment.PaymentRejectedException;
import ru.yandex.market_app.payment.PaymentReceipt;
import ru.yandex.market_app.payment.PaymentServiceUnavailableException;
import ru.yandex.market_app.repository.BasketRepository;
import ru.yandex.market_app.repository.OrderRepository;
import ru.yandex.market_app.repository.ProductRepository;
import ru.yandex.market_app.service.OrderService;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.UUID;

@RequiredArgsConstructor
@Service
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final BasketRepository basketRepository;
    private final ProductRepository productRepository;
    private final MarketMapper marketMapper;
    private final PaymentGateway paymentGateway;
    private final PaymentClientProperties paymentClientProperties;
    private final TransactionalOperator transactionalOperator;

    @Transactional(readOnly = true)
    @Override
    public Mono<GetListOrderModelDto> getOrders(@NonNull Long userId) {
        return orderRepository.findAllByUserId(userId)
            .concatMap(order -> productRepository.findOrderItems(order.getId(), userId)
                .collectList()
                .map(items -> marketMapper.toGetOrderModelDto(order, items)))
            .collectList()
            .map(orders -> GetListOrderModelDto.builder().orders(orders).build());
    }

    @Transactional(readOnly = true)
    @Override
    public Mono<GetOrderModelDto> getOrder(@NonNull Long userId, @NonNull Long id) {
        return orderRepository.findByIdAndUserId(id, userId)
            .switchIfEmpty(Mono.error(
                new NoSuchElementException("Заказ с id %d не найден".formatted(id))
            ))
            .flatMap(order -> productRepository.findOrderItems(order.getId(), userId)
                .collectList()
                .map(items -> marketMapper.toGetOrderModelDto(order, items)));
    }

    @Override
    public Mono<Long> completeOrder(@NonNull Long userId, @NonNull UUID paymentAccountId) {
        return orderRepository.findPendingByUserId(userId)
            .switchIfEmpty(Mono.defer(() -> prepareCheckout(userId, paymentAccountId)
                .as(transactionalOperator::transactional)))
            .flatMap(order -> claimAndProcessCheckout(userId, paymentAccountId, order));
    }

    private Mono<Order> prepareCheckout(
        Long userId,
        UUID paymentAccountId
    ) {
        return basketRepository.findActiveBasketForUpdate(userId)
            .flatMap(basket -> orderRepository.createPending(
                    basket.getId(),
                    userId,
                    BigDecimal.ZERO,
                    paymentAccountId,
                    UUID.randomUUID()
                )
                .switchIfEmpty(Mono.error(new IllegalStateException(
                    "Не удалось создать ожидающий оплаты заказ"
                )))
                .flatMap(this::ensureItemsSnapshot)
                .flatMap(order -> markBasketCheckout(order).thenReturn(order)))
            .switchIfEmpty(Mono.defer(() -> orderRepository.findPendingByUserId(userId)
                .switchIfEmpty(Mono.error(
                    new NoSuchElementException("Активная корзина не найдена")
                ))));
    }

    private Mono<Long> claimAndProcessCheckout(
        Long userId,
        UUID paymentAccountId,
        Order order
    ) {
        if (!paymentAccountId.equals(order.getPaymentAccountId())) {
            return Mono.error(new IllegalStateException(
                "Платёжный счёт ожидающего заказа не совпадает с текущим пользователем"
            ));
        }

        UUID attemptId = UUID.randomUUID();
        return orderRepository.claimPayment(
                order.getId(),
                userId,
                attemptId,
                paymentClaimLease().toMillis()
            )
            .switchIfEmpty(Mono.error(new PaymentServiceUnavailableException(
                "Платёж этого заказа уже обрабатывается"
            )))
            .as(transactionalOperator::transactional)
            .flatMap(claim -> processCheckout(
                userId,
                order.toBuilder()
                    .paymentAttempted(claim.previouslyAttempted())
                    .build(),
                attemptId
            ));
    }

    private Mono<Long> processCheckout(
        Long userId,
        Order order,
        UUID attemptId
    ) {
        return confirmPayment(order)
            .onErrorResume(error -> handlePaymentFailure(
                userId,
                order,
                attemptId,
                error
            ))
            .then(Mono.defer(() -> finalizeCheckout(userId, order, attemptId)
                .as(transactionalOperator::transactional)
                .onErrorResume(error -> recoverFinalization(
                    userId,
                    order,
                    attemptId,
                    error
                ))));
    }

    private Mono<PaymentReceipt> confirmPayment(Order order) {
        return paymentGateway.pay(
                order.getPaymentAccountId(),
                order.getPaymentRequestId(),
                order.getSum()
            )
            .onErrorResume(PaymentServiceUnavailableException.class, firstError ->
                Mono.delay(paymentClientProperties.retryDelay())
                    .then(Mono.defer(() -> paymentGateway.pay(
                        order.getPaymentAccountId(),
                        order.getPaymentRequestId(),
                        order.getSum()
                    )))
                    .onErrorMap(
                        secondError -> !(secondError instanceof InsufficientFundsException),
                        secondError -> ambiguousPaymentFailure(firstError, secondError)
                    ));
    }

    private Mono<PaymentReceipt> handlePaymentFailure(
        Long userId,
        Order order,
        UUID attemptId,
        Throwable error
    ) {
        if (canSafelyCancelCheckout(error, order.isPaymentAttempted())) {
            return cancelCheckout(userId, order, attemptId)
                .as(transactionalOperator::transactional)
                .then(Mono.error(error));
        }

        Throwable reportedError = order.isPaymentAttempted()
            ? preservePreviouslyAmbiguousPayment(error)
            : error;
        return releasePaymentClaim(userId, order, attemptId)
            .as(transactionalOperator::transactional)
            .then(Mono.error(reportedError));
    }

    private Mono<Long> recoverFinalization(
        Long userId,
        Order order,
        UUID attemptId,
        Throwable error
    ) {
        return orderRepository.findCompletedId(order.getId(), userId)
            .switchIfEmpty(Mono.defer(() ->
                releasePaymentClaim(userId, order, attemptId)
                    .as(transactionalOperator::transactional)
                    .then(orderRepository.findCompletedId(order.getId(), userId))
                    .switchIfEmpty(Mono.error(error))
            ));
    }

    private Mono<Order> ensureItemsSnapshot(Order order) {
        return orderRepository.createItemsSnapshot(order.getId(), order.getBasketId())
            .then(orderRepository.countItemsSnapshot(order.getId()))
            .filter(snapshotSize -> snapshotSize > 0)
            .switchIfEmpty(Mono.error(
                new NoSuchElementException("Корзина пуста")
            ))
            .then(orderRepository.updatePendingSumFromSnapshot(
                order.getId(),
                order.getUserId()
            ))
            .switchIfEmpty(Mono.error(new IllegalStateException(
                "Не удалось вычислить сумму сохранённого состава заказа"
            )))
            .map(total -> order.toBuilder().sum(total).build());
    }

    private Mono<Void> markBasketCheckout(Order order) {
        return basketRepository.markCheckout(order.getUserId(), order.getBasketId())
            .filter(Boolean::booleanValue)
            .switchIfEmpty(Mono.error(new IllegalStateException(
                "Не удалось зафиксировать начало оформления корзины"
            )))
            .then();
    }

    private Mono<Long> finalizeCheckout(Long userId, Order order, UUID attemptId) {
        Mono<Long> alreadyCompleted = Mono.defer(() ->
            orderRepository.findCompletedId(order.getId(), userId)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                    "Состояние оплаченного заказа не удалось восстановить"
                )))
        );

        return basketRepository.findCheckoutBasketByIdForUpdate(userId, order.getBasketId())
            .flatMap(basket -> orderRepository.markCompleted(
                    order.getId(),
                    userId,
                    attemptId
                )
                .switchIfEmpty(Mono.error(new IllegalStateException(
                    "Не удалось подтвердить ожидающий заказ"
                )))
                .flatMap(orderId -> basketRepository.closeCheckout(userId, basket.getId())
                    .filter(Boolean::booleanValue)
                    .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Не удалось закрыть оплаченную корзину"
                    )))
                    .thenReturn(orderId)))
            .switchIfEmpty(alreadyCompleted);
    }

    private Mono<Void> cancelCheckout(Long userId, Order order, UUID attemptId) {
        return basketRepository.findCheckoutBasketByIdForUpdate(userId, order.getBasketId())
            .switchIfEmpty(Mono.error(new IllegalStateException(
                "Ожидающая оплаты корзина не найдена"
            )))
            .flatMap(basket -> orderRepository.deletePending(
                    order.getId(),
                    userId,
                    attemptId
                )
                .filter(Boolean::booleanValue)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                    "Не удалось отменить ожидающий оплаты заказ"
                )))
                .then(basketRepository.restoreActive(userId, basket.getId()))
                .filter(Boolean::booleanValue)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                    "Не удалось вернуть корзину к редактированию"
                )))
                .then())
            .then();
    }

    private Mono<Void> releasePaymentClaim(Long userId, Order order, UUID attemptId) {
        return orderRepository.releasePaymentClaim(order.getId(), userId, attemptId)
            .flatMap(released -> {
                if (released) {
                    return Mono.empty();
                }

                return orderRepository.findCompletedId(order.getId(), userId)
                    .hasElement()
                    .flatMap(completed -> completed
                        ? Mono.empty()
                        : Mono.error(new IllegalStateException(
                            "Не удалось освободить незавершённую платёжную попытку"
                        )));
            });
    }

    private boolean canSafelyCancelCheckout(
        Throwable error,
        boolean paymentPreviouslyAttempted
    ) {
        return error instanceof InsufficientFundsException
            || error instanceof PaymentRejectedException && !paymentPreviouslyAttempted;
    }

    private Throwable ambiguousPaymentFailure(
        PaymentServiceUnavailableException firstError,
        Throwable secondError
    ) {
        var ambiguous = new PaymentServiceUnavailableException(
            "Результат платежа не удалось однозначно подтвердить",
            secondError
        );
        ambiguous.addSuppressed(firstError);
        return ambiguous;
    }

    private Throwable preservePreviouslyAmbiguousPayment(Throwable error) {
        if (error instanceof PaymentServiceUnavailableException) {
            return error;
        }

        return new PaymentServiceUnavailableException(
            "Результат предыдущей попытки платежа требует подтверждения",
            error
        );
    }

    private Duration paymentClaimLease() {
        Duration perAttempt = paymentClientProperties.responseTimeout()
            .plus(paymentClientProperties.connectTimeout());
        return perAttempt
            .multipliedBy(2)
            .plus(paymentClientProperties.retryDelay())
            .plusSeconds(1);
    }
}
