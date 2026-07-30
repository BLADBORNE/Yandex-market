package ru.yandex.market_app.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import ru.yandex.market_app.model.UserAccount;

import java.io.Serial;
import java.util.List;
import java.util.UUID;

public final class MarketUserPrincipal extends User {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Long userId;
    private final UUID paymentAccountId;

    public MarketUserPrincipal(UserAccount account) {
        super(
            account.username(),
            account.passwordHash(),
            account.enabled(),
            true,
            true,
            true,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        this.userId = account.id();
        this.paymentAccountId = account.paymentAccountId();
    }

    public Long userId() {
        return userId;
    }

    public UUID paymentAccountId() {
        return paymentAccountId;
    }
}
