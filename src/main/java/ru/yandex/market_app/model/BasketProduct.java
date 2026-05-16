package ru.yandex.market_app.model;

import jakarta.persistence.Table;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.FetchType;
import jakarta.persistence.MapsId;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Entity;
import lombok.Getter;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.AccessLevel;
import lombok.Setter;

import java.io.Serializable;

@Getter
@Table(schema = "market", name = "basket_product")
@Builder(toBuilder = true)
@Entity
@NoArgsConstructor(force = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class BasketProduct {

    @EmbeddedId
    private BasketProductId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    @MapsId("productId")
    private final Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "basket_id", nullable = false)
    @MapsId("basketId")
    private final Basket basket;

    @Setter
    @Column(nullable = false)
    private Integer count;

    @Getter
    @Builder(toBuilder = true)
    @Embeddable
    @NoArgsConstructor(force = true)
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @EqualsAndHashCode
    public static class BasketProductId implements Serializable {

        private final Long productId;

        private final Long basketId;
    }
}
