package ru.yandex.market_app.service;

import org.springframework.lang.NonNull;
import ru.yandex.market_app.dto.BasketDto;
import ru.yandex.market_app.dto.GetProductCartModelDto;
import ru.yandex.market_app.dto.ProductResultDto;
import ru.yandex.market_app.model.Basket;
import ru.yandex.market_app.model.ProductAction;

public interface BasketService {

    void changeProductCountFromStartPage(@NonNull Long id, @NonNull ProductAction productAction);

    ProductResultDto changeProductCountFromItemPage(@NonNull Long id, @NonNull ProductAction productAction);

    void changeProductCountFromCartPage(@NonNull Long id, @NonNull ProductAction productAction);

    GetProductCartModelDto getCart();

    BasketDto findLazyActiveBasket();

    Basket getReferenceById(Long id);

    void closeActiveBasket();
}
