package ru.yandex.market_app.model;

import jakarta.persistence.Table;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToOne;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Column;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

@SuperBuilder(toBuilder = true)
@Getter
@Table(schema = "market", name = "order")
@Entity
@NoArgsConstructor(force = true)
public final class Order extends BaseModel {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "basket_id", nullable = false)
    private final Basket basket;

    @Column(nullable = false)
    private final BigDecimal sum;
}
