package com.example.electronichome.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class ReadingValidationTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    void acceptsValuesAtOrAbovePrevious() {
        assertThat(ReadingValidation.check(d("250.125"), d("245.300"))).isNull();
        assertThat(ReadingValidation.check(d("245.300"), d("245.300"))).isNull();
        assertThat(ReadingValidation.check(d("245.3"), d("245.300"))).isNull();
    }

    @Test
    void acceptsAnyValidValueWithoutPrevious() {
        assertThat(ReadingValidation.check(d("0"), null)).isNull();
        assertThat(ReadingValidation.check(d("0.000"), null)).isNull();
        assertThat(ReadingValidation.check(d("5"), null)).isNull();
    }

    @Test
    void rejectsValueBelowPrevious() {
        assertThat(ReadingValidation.check(d("245.299"), d("245.300"))).isEqualTo("VALUE_LESS_THAN_PREVIOUS");
    }

    @Test
    void rejectsMissingAndNegativeValues() {
        assertThat(ReadingValidation.check(null, null)).isEqualTo("INVALID_VALUE");
        assertThat(ReadingValidation.check(d("-0.001"), null)).isEqualTo("INVALID_VALUE");
        assertThat(ReadingValidation.check(d("-1"), d("0"))).isEqualTo("INVALID_VALUE");
    }

    @Test
    void allowsAtMostThreeFractionDigitsIgnoringTrailingZeros() {
        assertThat(ReadingValidation.check(d("1.2345"), null)).isEqualTo("INVALID_VALUE");
        assertThat(ReadingValidation.check(d("1.2340"), null)).isNull();
        assertThat(ReadingValidation.check(d("1.2300000"), null)).isNull();
    }

    @Test
    void allowsAtMostNineIntegerDigits() {
        assertThat(ReadingValidation.check(d("999999999.999"), null)).isNull();
        assertThat(ReadingValidation.check(d("1000000000"), null)).isEqualTo("INVALID_VALUE");
        assertThat(ReadingValidation.check(d("1E+10"), null)).isEqualTo("INVALID_VALUE");
    }

    @Test
    void invalidValueWinsOverComparisonWithPrevious() {
        assertThat(ReadingValidation.check(d("1.2345"), d("5"))).isEqualTo("INVALID_VALUE");
    }
}
