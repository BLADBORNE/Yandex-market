package ru.yandex.market_app.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Table(schema = "market", name = "order")
public final class Order {

    @Id
    private Long id;

    @Column("basket_id")
    private Long basketId;

    @Column("user_id")
    private Long userId;

    private BigDecimal sum;

    private Status status;

    @Column("payment_account_id")
    private UUID paymentAccountId;

    @Column("payment_request_id")
    private UUID paymentRequestId;

    @Column("payment_attempted")
    private boolean paymentAttempted;

    public enum Status {

        PENDING,

        COMPLETED
    }
}
