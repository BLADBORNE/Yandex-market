package ru.yandex.market_app.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.yandex.market_app.dto.BasketDto;
import ru.yandex.market_app.dto.GetListOrderModelDto;
import ru.yandex.market_app.dto.GetOrderModelDto;
import ru.yandex.market_app.mapper.MarketMapper;
import ru.yandex.market_app.repository.OrderRepository;
import ru.yandex.market_app.service.BasketService;
import ru.yandex.market_app.service.OrderService;

import java.util.NoSuchElementException;

@RequiredArgsConstructor
@Service
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;

    private final BasketService basketService;

    private final MarketMapper marketMapper;

    @Transactional(readOnly = true)
    @Override
    public GetListOrderModelDto getOrders() {

        return marketMapper.toGetAllOrderModelDto(orderRepository.findAll());
    }

    @Transactional(readOnly = true)
    @Override
    public GetOrderModelDto getOrder(@NonNull Long id) {

        return marketMapper.toGetOrderModelDto(
            orderRepository.findById(id).orElseThrow(() -> new NoSuchElementException("Заказ с id %d не найден".formatted(id)))
        );
    }

    @Transactional
    @Override
    public Long completeOrder() {
        BasketDto basket = basketService.findLazyActiveBasket();
        basketService.closeActiveBasket();

        return orderRepository.save(
            marketMapper.toOrder(basketService.getReferenceById(basket.id()), basket.totalSum())
        ).getId();
    }
}
