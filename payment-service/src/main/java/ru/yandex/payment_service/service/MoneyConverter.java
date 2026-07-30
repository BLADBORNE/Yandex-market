package ru.yandex.payment_service.service;

import ru.yandex.payment_service.exception.InvalidPaymentRequestException;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class MoneyConverter {

    private static final int MONEY_SCALE = 2;

    private MoneyConverter() {
    }

    static long positiveAmountToMinorUnits(BigDecimal amount) {
        long minorUnits = toMinorUnits(amount, "Сумма платежа");
        if (minorUnits <= 0) {
            throw new InvalidPaymentRequestException("Сумма платежа должна быть больше нуля");
        }
        return minorUnits;
    }

    static long nonNegativeAmountToMinorUnits(BigDecimal amount) {
        long minorUnits = toMinorUnits(amount, "Начальный баланс");
        if (minorUnits < 0) {
            throw new IllegalArgumentException("Начальный баланс не может быть отрицательным");
        }
        return minorUnits;
    }

    static BigDecimal fromMinorUnits(long amount) {
        return BigDecimal.valueOf(amount, MONEY_SCALE);
    }

    private static long toMinorUnits(BigDecimal amount, String fieldName) {
        if (amount == null) {
            throw new InvalidPaymentRequestException("%s не указана".formatted(fieldName));
        }

        try {
            return amount
                .setScale(MONEY_SCALE, RoundingMode.UNNECESSARY)
                .movePointRight(MONEY_SCALE)
                .longValueExact();
        } catch (ArithmeticException exception) {
            throw new InvalidPaymentRequestException(
                "%s должна содержать не более двух знаков после запятой".formatted(fieldName)
            );
        }
    }
}
