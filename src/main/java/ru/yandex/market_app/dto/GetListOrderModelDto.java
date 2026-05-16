package ru.yandex.market_app.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record GetListOrderModelDto(List<GetOrderModelDto> orders) {

}
