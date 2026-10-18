package com.example.electronichome.account;

import java.math.BigDecimal;

/** Rules for a reading value; {@code numeric(12,3)} in the database allows 9 integer and 3 fraction digits. */
final class ReadingValidation {

    static final String INVALID_VALUE = "INVALID_VALUE";
    static final String VALUE_LESS_THAN_PREVIOUS = "VALUE_LESS_THAN_PREVIOUS";

    private static final int MAX_SCALE = 3;
    private static final int MAX_INTEGER_DIGITS = 9;

    private ReadingValidation() {
    }

    /** Returns an error code, or {@code null} when the value may be saved after {@code previous} (which may be null). */
    static String check(BigDecimal value, BigDecimal previous) {
        if (value == null || value.signum() < 0) {
            return INVALID_VALUE;
        }
        BigDecimal stripped = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        if (stripped.scale() > MAX_SCALE || stripped.precision() - stripped.scale() > MAX_INTEGER_DIGITS) {
            return INVALID_VALUE;
        }
        if (previous != null && value.compareTo(previous) < 0) {
            return VALUE_LESS_THAN_PREVIOUS;
        }
        return null;
    }
}
