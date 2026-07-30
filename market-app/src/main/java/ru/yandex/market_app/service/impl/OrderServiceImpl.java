package ru.yandex.market_app.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.dto.GetListOrderModelDto;
import ru.yandex.market_app.dto.GetOrderModelDto;
import ru.yandex.market_app.mapper.MarketMapper;
import ru.yandex.market_app.payment.PaymentGateway;
import ru.yandex.market_app.payment.PaymentReceipt;
import ru.yandex.market_app.payment.PaymentServiceUnavailableException;
import ru.yandex.market_app.repository.BasketRepository;
import ru.yandex.market_app.repository.OrderRepository;
import ru.yandex.market_app.repository.ProductRepository;
import ru.yandex.market_app.service.OrderService;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@RequiredArgsConstructor
@Service
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final BasketRepository basketRepository;
    private final ProductRepository productRepository;
    private final MarketMapper marketMapper;
    private final PaymentGateway paymentGateway;
    private final TransactionalOperator transactionalOperator;

    @Transactional(readOnly = true)
    @Override
    public Mono<GetListOrderModelDto> getOrders() {
        return orderRepository.findAll()
            .concatMap(order -> productRepository.findOrderItems(order.getId())
                .collectList()
                .map(items -> marketMapper.toGetOrderModelDto(order, items)))
            .collectList()
            .map(orders -> GetListOrderModelDto.builder().orders(orders).build());
    }

    @Transactional(readOnly = true)
    @Override
    public Mono<GetOrderModelDto> getOrder(@NonNull Long id) {
        return orderRepository.findById(id)
            .switchIfEmpty(Mono.error(
                new NoSuchElementException("Заказ с id %d не найден".formatted(id))
            ))
            .flatMap(order -> productRepository.findOrderItems(order.getId())
                .collectList()
                .map(items -> marketMapper.toGetOrderModelDto(order, items)));
    }

    @Override
    public Mono<Long> completeOrder() {
        return basketRepository.findActiveBasket()
            .switchIfEmpty(Mono.error(new NoSuchElementException("Активная корзина не найдена")))
            .flatMap(basket -> executeCheckout(basket.getId(), true));
    }

    private Mono<Long> executeCheckout(Long basketId, boolean recoveryAllowed) {
        return Mono.defer(() -> {
            var paymentAccepted = new AtomicBoolean(false);

            return createOrder(basketId, paymentAccepted)
                .as(transactionalOperator::transactional)
                .onErrorResume(error -> recoveryAllowed && paymentAccepted.get()
                    ? reconcileCheckout(basketId, error)
                    : Mono.error(error));
        });
    }

    private Mono<Long> createOrder(Long basketId, AtomicBoolean paymentAccepted) {
        return basketRepository.findActiveBasketByIdForUpdate(basketId)
            .switchIfEmpty(Mono.error(new NoSuchElementException("Активная корзина не найдена")))
            .then(calculateBasketTotal(basketId))
            .flatMap(total -> orderRepository.create(basketId, total)
                .flatMap(orderId -> createItemsSnapshot(orderId, basketId))
                .flatMap(orderId -> confirmPayment(basketId, total)
                    .doOnNext(receipt -> paymentAccepted.set(true))
                    .thenReturn(orderId))
                .flatMap(orderId -> closeBasket(orderId, basketId)));
    }

    private Mono<PaymentReceipt> confirmPayment(Long basketId, BigDecimal total) {
        UUID requestId = paymentRequestId(basketId);

        return paymentGateway.pay(requestId, total)
            .onErrorResume(PaymentServiceUnavailableException.class, firstError ->
                paymentGateway.pay(requestId, total)
                    .onErrorMap(secondError -> addSuppressed(secondError, firstError)));
    }

    private Mono<Long> reconcileCheckout(Long basketId, Throwable checkoutError) {
        return orderRepository.findIdByBasketId(basketId)
            .switchIfEmpty(Mono.defer(() -> executeCheckout(basketId, false)))
            .onErrorMap(recoveryError -> addSuppressed(recoveryError, checkoutError));
    }

    private Throwable addSuppressed(Throwable error, Throwable suppressed) {
        if (error != suppressed) {
            error.addSuppressed(suppressed);
        }
        return error;
    }

    private Mono<BigDecimal> calculateBasketTotal(Long basketId) {
        return basketRepository.calculateBasketTotal(basketId)
            .switchIfEmpty(Mono.error(new NoSuchElementException("Корзина пуста")));
    }

    private Mono<Long> createItemsSnapshot(Long orderId, Long basketId) {
        return orderRepository.createItemsSnapshot(orderId, basketId)
            .filter(snapshotSize -> snapshotSize > 0)
            .switchIfEmpty(Mono.error(
                new IllegalStateException("Не удалось сохранить состав заказа")
            ))
            .thenReturn(orderId);
    }

    private Mono<Long> closeBasket(Long orderId, Long basketId) {
        return basketRepository.closeBasket(basketId)
            .filter(Boolean::booleanValue)
            .switchIfEmpty(Mono.error(new IllegalStateException("Не удалось закрыть корзину")))
            .thenReturn(orderId);
    }

    private UUID paymentRequestId(Long basketId) {
        return UUID.nameUUIDFromBytes(
            ("market-basket:" + basketId).getBytes(StandardCharsets.UTF_8)
        );
    }
}
