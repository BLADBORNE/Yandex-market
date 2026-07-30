package ru.yandex.market_app.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.dto.GetListOrderModelDto;
import ru.yandex.market_app.dto.GetOrderModelDto;
import ru.yandex.market_app.mapper.MarketMapper;
import ru.yandex.market_app.model.Basket;
import ru.yandex.market_app.payment.PaymentGateway;
import ru.yandex.market_app.repository.BasketRepository;
import ru.yandex.market_app.repository.OrderRepository;
import ru.yandex.market_app.repository.ProductRepository;
import ru.yandex.market_app.service.OrderService;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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

    @Transactional
    @Override
    public Mono<Long> completeOrder() {
        return basketRepository.findActiveBasketForUpdate()
            .switchIfEmpty(Mono.error(new NoSuchElementException("Активная корзина не найдена")))
            .flatMap(this::createOrder);
    }

    private Mono<Long> createOrder(Basket basket) {
        Long basketId = basket.getId();

        return calculateBasketTotal(basketId)
            .flatMap(total -> orderRepository.create(basketId, total)
                .flatMap(orderId -> createItemsSnapshot(orderId, basketId))
                .delayUntil(orderId -> paymentGateway.pay(paymentRequestId(basketId), total))
                .flatMap(orderId -> closeBasket(orderId, basketId)));
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
