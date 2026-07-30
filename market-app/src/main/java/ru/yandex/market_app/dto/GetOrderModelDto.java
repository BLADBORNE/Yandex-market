package ru.yandex.market_app.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

@Builder
public record GetOrderModelDto(Long id, List<ItemDto> items, BigDecimal totalSum) {

}
