package ru.yandex.market_app.dto;

import lombok.Builder;

import java.math.BigDecimal;

@Builder(toBuilder = true)
public record ItemDto(Long id, String title, BigDecimal price, Integer count) {

}
