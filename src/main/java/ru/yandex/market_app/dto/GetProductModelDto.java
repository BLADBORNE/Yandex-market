package ru.yandex.market_app.dto;

import lombok.Builder;
import org.springframework.data.domain.Page;
import ru.yandex.market_app.util.ProductPageableUtil;

import java.util.Collections;
import java.util.List;

@Builder
public record GetProductModelDto(
    List<List<ProductResultDto>> items,
    String search,
    String sort,
    PageableResult paging
) {
    public static GetProductModelDto EMPTY(String search, ProductPageableUtil.ProductSort sort) {
        var page = Page.empty();

        return GetProductModelDto.builder()
            .items(Collections.emptyList())
            .search(search)
            .sort(sort.name())
            .paging(PageableResult.INIT(page.getSize(), page.getNumber(), page.hasPrevious(), page.hasNext()))
            .build();
    }
}
