package ru.yandex.market_app.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.CrudRepository;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Repository;
import ru.yandex.market_app.model.Order;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends CrudRepository<Order, Long> {

    @Override
    @NonNull
    @EntityGraph(attributePaths = {"basket", "basket.basketProducts", "basket.basketProducts.product"})
    List<Order> findAll();

    @Override
    @NonNull
    @EntityGraph(attributePaths = {"basket", "basket.basketProducts", "basket.basketProducts.product"})
    Optional<Order> findById(@NonNull Long id);
}
