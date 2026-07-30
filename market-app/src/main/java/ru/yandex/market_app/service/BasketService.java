package ru.yandex.market_app.service;

import org.springframework.lang.NonNull;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.dto.BasketDto;
import ru.yandex.market_app.dto.GetProductCartModelDto;
import ru.yandex.market_app.model.Basket;
import ru.yandex.market_app.model.ProductAction;

public interface BasketService {

    Mono<Void> changeProductCountFromStartPage(
        @NonNull Long userId,
        @NonNull Long id,
        @NonNull ProductAction productAction
    );

    Mono<Void> changeProductCountFromItemPage(
        @NonNull Long userId,
        @NonNull Long id,
        @NonNull ProductAction productAction
    );

    Mono<Void> changeProductCountFromCartPage(
        @NonNull Long userId,
        @NonNull Long id,
        @NonNull ProductAction productAction
    );

    Mono<GetProductCartModelDto> getCart(@NonNull Long userId);

    Mono<BasketDto> findLazyActiveBasket(@NonNull Long userId);

    Mono<Basket> getReferenceById(@NonNull Long userId, @NonNull Long id);

    Mono<Void> closeActiveBasket(@NonNull Long userId, @NonNull Long basketId);
}
