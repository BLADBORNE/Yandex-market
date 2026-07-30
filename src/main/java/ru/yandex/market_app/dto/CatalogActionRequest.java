package ru.yandex.market_app.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import ru.yandex.market_app.model.ProductAction;
import ru.yandex.market_app.util.ProductPageableUtil;

@Getter
@Setter
public class CatalogActionRequest {

    @NotNull
    private Long id;

    private String search = "";

    private ProductPageableUtil.ProductSort sort = ProductPageableUtil.ProductSort.NO;

    @Min(1)
    private int pageNumber = 1;

    @Min(1)
    @Max(100)
    private int pageSize = 5;

    @NotNull
    private ProductAction action;
}
