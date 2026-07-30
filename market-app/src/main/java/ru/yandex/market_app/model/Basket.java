package ru.yandex.market_app.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(schema = "market", name = "basket")
public final class Basket {

    @Id
    private Long id;

    @Column("user_id")
    private Long userId;

    private Status status;

    public enum Status {

        ACTIVE,

        CHECKOUT,

        CLOSED
    }
}
