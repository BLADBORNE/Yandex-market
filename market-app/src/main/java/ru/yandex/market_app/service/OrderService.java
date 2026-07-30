package ru.yandex.market_app.service;

import org.springframework.lang.NonNull;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.dto.GetListOrderModelDto;
import ru.yandex.market_app.dto.GetOrderModelDto;

import java.util.UUID;

public interface OrderService {

    Mono<GetListOrderModelDto> getOrders(@NonNull Long userId);

    Mono<GetOrderModelDto> getOrder(@NonNull Long userId, @NonNull Long id);

    Mono<Long> completeOrder(@NonNull Long userId, @NonNull UUID paymentAccountId);
}
