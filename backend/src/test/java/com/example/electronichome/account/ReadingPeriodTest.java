package com.example.electronichome.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class ReadingPeriodTest {

    private static ReadingPeriod period(String date) {
        return ReadingPeriod.containing(LocalDate.parse(date), 25);
    }

    private static void assertPeriod(String date, String start, String end) {
        ReadingPeriod period = period(date);
        assertThat(period.start()).as("start of the period of %s", date).isEqualTo(LocalDate.parse(start));
        assertThat(period.end()).as("end of the period of %s", date).isEqualTo(LocalDate.parse(end));
        assertThat(period.contains(LocalDate.parse(date))).isTrue();
    }

    @Test
    void middleOfThePeriod() {
        assertPeriod("2026-10-18", "2026-09-26", "2026-10-25");
        assertThat(period("2026-10-18").next()).isEqualTo(LocalDate.parse("2026-10-26"));
    }

    @Test
    void closingDayBelongsToTheEndingPeriod() {
        assertPeriod("2026-10-25", "2026-09-26", "2026-10-25");
    }

    @Test
    void theDayAfterTheClosingDayStartsTheNextPeriod() {
        assertPeriod("2026-10-26", "2026-10-26", "2026-11-25");
    }

    @Test
    void firstAndLastDaysOfAMonth() {
        assertPeriod("2026-10-01", "2026-09-26", "2026-10-25");
        assertPeriod("2026-10-31", "2026-10-26", "2026-11-25");
        assertPeriod("2026-01-31", "2026-01-26", "2026-02-25");
    }

    @Test
    void periodsCrossTheEdgeOfAYear() {
        assertPeriod("2026-12-28", "2026-12-26", "2027-01-25");
        assertPeriod("2027-01-01", "2026-12-26", "2027-01-25");
        assertPeriod("2027-01-26", "2027-01-26", "2027-02-25");
    }

    @Test
    void februaryAndLeapYears() {
        assertPeriod("2026-02-25", "2026-01-26", "2026-02-25");
        assertPeriod("2026-02-26", "2026-02-26", "2026-03-25");
        assertPeriod("2028-02-29", "2028-02-26", "2028-03-25");
        assertPeriod("2026-03-01", "2026-02-26", "2026-03-25");
    }

    @Test
    void containsOnlyDatesOfItsOwnPeriod() {
        ReadingPeriod october = period("2026-10-18");

        assertThat(october.contains(LocalDate.parse("2026-09-25"))).isFalse();
        assertThat(october.contains(LocalDate.parse("2026-09-26"))).isTrue();
        assertThat(october.contains(LocalDate.parse("2026-10-25"))).isTrue();
        assertThat(october.contains(LocalDate.parse("2026-10-26"))).isFalse();
    }

    @Test
    void everyDateOfAYearBelongsToExactlyOneConsecutivePeriod() {
        LocalDate day = LocalDate.parse("2026-01-01");
        ReadingPeriod previous = ReadingPeriod.containing(day, 25);
        for (int i = 0; i < 400; i++) {
            LocalDate current = day.plusDays(i);
            ReadingPeriod period = ReadingPeriod.containing(current, 25);
            if (!period.equals(previous)) {
                assertThat(period.start()).as("a new period starts right after the previous one").isEqualTo(previous.next());
                previous = period;
            }
            assertThat(period.contains(current)).isTrue();
        }
    }

    @Test
    void otherClosingDays() {
        assertThat(ReadingPeriod.containing(LocalDate.parse("2026-10-01"), 1).end()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(ReadingPeriod.containing(LocalDate.parse("2026-10-02"), 1).end()).isEqualTo(LocalDate.parse("2026-11-01"));
        ReadingPeriod last = ReadingPeriod.containing(LocalDate.parse("2026-10-29"), 28);
        assertThat(last.start()).isEqualTo(LocalDate.parse("2026-10-29"));
        assertThat(last.end()).isEqualTo(LocalDate.parse("2026-11-28"));
    }

    @Test
    void closingDayMustExistInEveryMonth() {
        assertThat(new ReadingPeriodProperties(1, true).closingDay()).isEqualTo(1);
        assertThat(new ReadingPeriodProperties(28, true).closingDay()).isEqualTo(28);
        assertThatThrownBy(() -> new ReadingPeriodProperties(0, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReadingPeriodProperties(29, true)).isInstanceOf(IllegalArgumentException.class);
    }
}
