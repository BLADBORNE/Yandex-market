package ru.yandex.market_app.repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.dto.ItemDto;
import ru.yandex.market_app.dto.ProductCartResultDto;
import ru.yandex.market_app.dto.ProductResultDto;
import ru.yandex.market_app.util.ProductPageableUtil.ProductSort;

public interface ProductQueryRepository {

    Flux<ProductResultDto> findCatalogPage(String search, ProductSort sort, int limit, long offset);

    Mono<ProductResultDto> findCatalogItem(Long id);

    Flux<ProductCartResultDto> findActiveCartItems();

    Flux<ItemDto> findOrderItems(Long orderId);
}
