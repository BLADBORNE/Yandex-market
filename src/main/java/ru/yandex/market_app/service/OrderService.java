package ru.yandex.market_app.service;

import org.springframework.lang.NonNull;
import ru.yandex.market_app.dto.GetListOrderModelDto;
import ru.yandex.market_app.dto.GetOrderModelDto;

public interface OrderService {

    GetListOrderModelDto getOrders();

    GetOrderModelDto getOrder(@NonNull Long id);

    Long completeOrder();
}
