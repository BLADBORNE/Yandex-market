package ru.yandex.market_app.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.yandex.market_app.dto.BasketDto;
import ru.yandex.market_app.model.Basket;
import ru.yandex.market_app.util.SqlUtil;

import java.util.Optional;

@Repository
public interface BasketRepository extends JpaRepository<Basket, Long> {

    @Query(SqlUtil.FIND_EAGER_BASKET_WITH_ENTITIES_ID)
    Optional<Basket> findEagerBasketByStatus(Basket.Status status);

    @Query(SqlUtil.FIND_ACTIVE_BASKET_WITH_TOTAL_SUM)
    Optional<BasketDto> findBasketByStatus(Basket.Status status);

    @Modifying
    @Query(SqlUtil.CLOSE_ACTIVE_BASKET)
    void closeActiveBasket();
}
