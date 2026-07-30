package ru.yandex.market_app.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table(schema = "market", name = "user_account")
public record UserAccount(
    @Id Long id,
    String username,
    @Column("password_hash") String passwordHash,
    boolean enabled,
    @Column("payment_account_id") UUID paymentAccountId
) {
}
