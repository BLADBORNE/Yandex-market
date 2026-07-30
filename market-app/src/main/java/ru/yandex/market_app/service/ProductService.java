package ru.yandex.market_app.service;

import org.springframework.data.domain.Pageable;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.dto.GetProductModelDto;
import ru.yandex.market_app.dto.ProductResultDto;
import ru.yandex.market_app.util.ProductPageableUtil;

public interface ProductService {

    Mono<GetProductModelDto> getProducts(
        @Nullable String search,
        @NonNull ProductPageableUtil.ProductSort sort,
        @NonNull Pageable pageable
    );

    Mono<ProductResultDto> getItem(Long itemId);
}
