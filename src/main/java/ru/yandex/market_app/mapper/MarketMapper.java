package ru.yandex.market_app.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.data.domain.Slice;
import ru.yandex.market_app.dto.GetListOrderModelDto;
import ru.yandex.market_app.dto.GetOrderModelDto;
import ru.yandex.market_app.dto.GetProductCartModelDto;
import ru.yandex.market_app.dto.GetProductModelDto;
import ru.yandex.market_app.dto.ItemDto;
import ru.yandex.market_app.dto.ProductCartResultDto;
import ru.yandex.market_app.dto.ProductResultDto;
import ru.yandex.market_app.model.Basket;
import ru.yandex.market_app.model.BasketProduct;
import ru.yandex.market_app.model.Order;
import ru.yandex.market_app.model.Product;

import java.math.BigDecimal;
import java.util.List;

@Mapper(componentModel = "spring",
    imports = {
        ru.yandex.market_app.model.BasketProduct.BasketProductId.class,
        org.springframework.data.domain.Page.class,
        ru.yandex.market_app.dto.PageableResult.class
    })
public interface MarketMapper {

    String INIT_PAGEABLE = "java(PageableResult.init(paging.getSize(), paging.getNumber() + 1, paging.hasPrevious(), paging.hasNext()))";

    @Mapping(target = "id", expression = "java(BasketProductId.builder().build())")
    BasketProduct toBasketProduct(Product product, Basket basket, int count);

    @Mapping(target = "id", ignore = true)
    Order toOrder(Basket basket, BigDecimal sum);

    @Mapping(target = "id", source = "order.id")
    @Mapping(target = "items", source = "order.basket.basketProducts")
    @Mapping(target = "totalSum", source = "order.sum")
    GetOrderModelDto toGetOrderModelDto(Order order);

    ItemDto toItemDto(Product product);

    Basket toBasket(Basket.Status status);

    GetProductCartModelDto toGetProductCartModelDto(List<ProductCartResultDto> items, BigDecimal total);

    @Mapping(target = "paging", expression = INIT_PAGEABLE)
    @Mapping(target = "sort", source = "sort")
    GetProductModelDto toGetProductModelDto(
        List<List<ProductResultDto>> items,
        String search,
        String sort,
        Slice<ProductResultDto> paging
    );

    default GetListOrderModelDto toGetAllOrderModelDto(List<Order> orders) {
        List<GetOrderModelDto> result = orders.stream().map(o -> GetOrderModelDto.builder()
                .id(o.getId())
                .totalSum(o.getSum())
                .items(toItemDtoList(o.getBasket().getBasketProducts()))
                .build())
            .toList();

        return GetListOrderModelDto.builder().orders(result).build();
    }

    default List<ItemDto> toItemDtoList(List<BasketProduct> basketProducts) {

        return basketProducts.stream()
            .map(bp -> toItemDto(bp.getProduct()).toBuilder().count(bp.getCount()).build())
            .toList();
    }
}
