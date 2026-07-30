package ru.yandex.market_app.repository;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.model.UserAccount;

@Repository
public interface UserAccountRepository extends ReactiveCrudRepository<UserAccount, Long> {

    Mono<UserAccount> findByUsername(String username);
}
