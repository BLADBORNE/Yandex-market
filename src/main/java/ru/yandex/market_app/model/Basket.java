package ru.yandex.market_app.model;

import jakarta.persistence.Table;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.FetchType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

import static jakarta.persistence.CascadeType.MERGE;
import static jakarta.persistence.CascadeType.PERSIST;

@SuperBuilder(toBuilder = true)
@Getter
@Table(schema = "market", name = "basket")
@Entity
@NoArgsConstructor(force = true)
public final class Basket extends BaseModel {

    @Builder.Default
    @OneToMany(mappedBy = "basket", fetch = FetchType.LAZY, cascade = {PERSIST, MERGE}, orphanRemoval = true)
    private final List<BasketProduct> basketProducts = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    private final Status status;

    public enum Status {

        ACTIVE,

        CLOSED
    }
}
