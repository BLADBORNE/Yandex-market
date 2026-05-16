package ru.yandex.market_app.model;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.FetchType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.List;

@SuperBuilder(toBuilder = true)
@Getter
@Table(schema = "market", name = "product")
@Entity
@NoArgsConstructor(force = true)
public final class Product extends BaseModel {

    @Column(nullable = false, unique = true)
    private final String title;

    @Column(nullable = false)
    private final String description;

    @Column(name = "img_path", nullable = false, unique = true)
    private final String imgPath;

    @Column(nullable = false)
    private final BigDecimal price;

    @OneToMany(mappedBy = "product", fetch = FetchType.LAZY)
    private final List<BasketProduct> basketProduct;
}
