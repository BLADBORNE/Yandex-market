package ru.yandex.market_app.dto;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record BasketDto(Long id, BigDecimal totalSum) {

}
