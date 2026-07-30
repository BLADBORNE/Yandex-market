package ru.yandex.market_app.repository;

import reactor.core.publisher.Flux;
import ru.yandex.market_app.dto.ItemDto;

public interface ProductQueryRepository {

    Flux<ItemDto> findOrderItems(Long orderId, Long userId);
}
