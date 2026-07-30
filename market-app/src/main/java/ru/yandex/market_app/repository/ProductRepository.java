package ru.yandex.market_app.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import ru.yandex.market_app.model.Product;

@Repository
public interface ProductRepository extends ReactiveCrudRepository<Product, Long>, ProductQueryRepository {
}
