package ru.yandex.market_app.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

@Builder
public record GetProductCartModelDto(List<ProductResultDto> items, BigDecimal total) {

}
