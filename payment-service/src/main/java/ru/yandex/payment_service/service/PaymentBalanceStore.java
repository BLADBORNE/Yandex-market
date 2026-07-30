package ru.yandex.payment_service.service;

import org.springframework.stereotype.Component;
import ru.yandex.payment_service.config.PaymentBalanceProperties;
import ru.yandex.payment_service.domain.PaymentResult;
import ru.yandex.payment_service.exception.IdempotencyConflictException;
import ru.yandex.payment_service.exception.InsufficientFundsException;
import ru.yandex.payment_service.exception.InvalidPaymentRequestException;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public final class PaymentBalanceStore {

    private final long initialBalanceInMinorUnits;
    private final ConcurrentMap<UUID, Account> accounts = new ConcurrentHashMap<>();

    public PaymentBalanceStore(PaymentBalanceProperties properties) {
        this.initialBalanceInMinorUnits = MoneyConverter.nonNegativeAmountToMinorUnits(
            properties.initial()
        );
    }

    public BigDecimal getBalance(UUID customerId) {
        return account(customerId).getBalance();
    }

    public PaymentResult debit(UUID customerId, UUID requestId, BigDecimal amount) {
        if (requestId == null) {
            throw new InvalidPaymentRequestException("Идентификатор запроса не указан");
        }

        long amountInMinorUnits = MoneyConverter.positiveAmountToMinorUnits(amount);
        return account(customerId).debit(requestId, amountInMinorUnits);
    }

    private Account account(UUID customerId) {
        if (customerId == null) {
            throw new InvalidPaymentRequestException("Идентификатор покупателя не указан");
        }

        return accounts.computeIfAbsent(customerId, this::newAccount);
    }

    private Account newAccount(UUID ignoredCustomerId) {
        return new Account(initialBalanceInMinorUnits);
    }

    private static final class Account {

        private final AtomicLong balanceInMinorUnits;
        private final ConcurrentMap<UUID, StoredPayment> completedPayments =
            new ConcurrentHashMap<>();

        private Account(long initialBalanceInMinorUnits) {
            this.balanceInMinorUnits = new AtomicLong(initialBalanceInMinorUnits);
        }

        private BigDecimal getBalance() {
            return MoneyConverter.fromMinorUnits(balanceInMinorUnits.get());
        }

        private PaymentResult debit(UUID requestId, long amountInMinorUnits) {
            StoredPayment payment = completedPayments.compute(
                requestId,
                (id, completedPayment) -> resolvePayment(id, amountInMinorUnits, completedPayment)
            );
            return payment.toResult();
        }

        private StoredPayment resolvePayment(
            UUID requestId,
            long amountInMinorUnits,
            StoredPayment completedPayment
        ) {
            if (completedPayment != null) {
                if (completedPayment.amountInMinorUnits() != amountInMinorUnits) {
                    throw new IdempotencyConflictException(requestId);
                }
                return completedPayment;
            }

            long remainingBalance = debitAtomically(amountInMinorUnits);
            return new StoredPayment(requestId, amountInMinorUnits, remainingBalance);
        }

        private long debitAtomically(long amountInMinorUnits) {
            while (true) {
                long currentBalance = balanceInMinorUnits.get();
                if (currentBalance < amountInMinorUnits) {
                    throw new InsufficientFundsException(
                        MoneyConverter.fromMinorUnits(currentBalance),
                        MoneyConverter.fromMinorUnits(amountInMinorUnits)
                    );
                }

                long remainingBalance = currentBalance - amountInMinorUnits;
                if (balanceInMinorUnits.compareAndSet(currentBalance, remainingBalance)) {
                    return remainingBalance;
                }
            }
        }
    }

    private record StoredPayment(
        UUID requestId,
        long amountInMinorUnits,
        long remainingBalanceInMinorUnits
    ) {

        private PaymentResult toResult() {
            return new PaymentResult(
                requestId,
                MoneyConverter.fromMinorUnits(amountInMinorUnits),
                MoneyConverter.fromMinorUnits(remainingBalanceInMinorUnits)
            );
        }
    }
}
