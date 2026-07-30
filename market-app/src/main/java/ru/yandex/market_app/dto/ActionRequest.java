package ru.yandex.market_app.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import ru.yandex.market_app.model.ProductAction;

@Getter
@Setter
public class ActionRequest {

    @NotNull
    private ProductAction action;
}
