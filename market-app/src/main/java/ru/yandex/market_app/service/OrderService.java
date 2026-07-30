package ru.yandex.market_app.service;

import org.springframework.lang.NonNull;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.dto.GetListOrderModelDto;
import ru.yandex.market_app.dto.GetOrderModelDto;

public interface OrderService {

    Mono<GetListOrderModelDto> getOrders();

    Mono<GetOrderModelDto> getOrder(@NonNull Long id);

    Mono<Long> completeOrder();
}
