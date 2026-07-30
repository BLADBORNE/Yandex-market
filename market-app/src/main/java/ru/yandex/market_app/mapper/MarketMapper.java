package ru.yandex.market_app.mapper;

import org.springframework.stereotype.Component;
import ru.yandex.market_app.cache.CachedProduct;
import ru.yandex.market_app.dto.GetOrderModelDto;
import ru.yandex.market_app.dto.ProductResultDto;
import ru.yandex.market_app.model.Order;
import ru.yandex.market_app.model.Product;

import java.util.List;

@Component
public final class MarketMapper {

    public ProductResultDto toProductResultDto(Product product, int count) {
        return ProductResultDto.builder()
            .id(product.getId())
            .title(product.getTitle())
            .description(product.getDescription())
            .imgPath(product.getImgPath())
            .price(product.getPrice())
            .count(count)
            .build();
    }

    public ProductResultDto toProductResultDto(CachedProduct product, int count) {
        return ProductResultDto.builder()
            .id(product.id())
            .title(product.title())
            .description(product.description())
            .imgPath(product.imgPath())
            .price(product.price())
            .count(count)
            .build();
    }

    public GetOrderModelDto toGetOrderModelDto(Order order, List<ru.yandex.market_app.dto.ItemDto> items) {
        return GetOrderModelDto.builder()
            .id(order.getId())
            .items(items)
            .totalSum(order.getSum())
            .build();
    }
}
