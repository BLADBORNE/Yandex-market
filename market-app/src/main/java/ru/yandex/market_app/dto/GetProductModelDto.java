package ru.yandex.market_app.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record GetProductModelDto(
    List<List<ProductResultDto>> items,
    String search,
    String sort,
    PageableResult paging
) {
}
