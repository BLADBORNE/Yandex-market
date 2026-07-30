package ru.yandex.market_app.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.yandex.market_app.repository.UserAccountRepository;

@Service
@RequiredArgsConstructor
public final class DatabaseReactiveUserDetailsService implements ReactiveUserDetailsService {

    private final UserAccountRepository userAccountRepository;

    @Override
    public Mono<UserDetails> findByUsername(String username) {
        return userAccountRepository.findByUsername(username)
            .map(MarketUserPrincipal::new);
    }
}
