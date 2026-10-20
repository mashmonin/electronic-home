package com.example.electronichome.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class RequestNumbersTest {

    @Test
    void theNumberIsTheLetterTheDateAndTheSequenceWithFourDigits() {
        assertThat(RequestNumbers.format(LocalDate.of(2026, 10, 19), 1)).isEqualTo("О-191026-0001");
        assertThat(RequestNumbers.format(LocalDate.of(2026, 10, 19), 37)).isEqualTo("О-191026-0037");
        assertThat(RequestNumbers.format(LocalDate.of(2027, 1, 5), 9999)).isEqualTo("О-050127-9999");
    }

    @Test
    void theSequenceJustGetsLongerAfterNineThousandNineHundredNinetyNine() {
        assertThat(RequestNumbers.format(LocalDate.of(2026, 10, 19), 10000)).isEqualTo("О-191026-10000");
    }

    @Test
    void theLetterIsCyrillic() {
        assertThat(RequestNumbers.format(LocalDate.of(2026, 10, 19), 1).codePointAt(0)).isEqualTo(0x041E);
        assertThat(RequestKind.GENERAL.letter().codePointAt(0)).isEqualTo(0x041E);
    }
}
