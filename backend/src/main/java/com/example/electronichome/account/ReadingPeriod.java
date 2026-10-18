package com.example.electronichome.account;

import java.time.LocalDate;

/**
 * A period in which one set of readings is taken: from the day after the closing day of the previous month up to
 * and including the closing day of the month in which the period ends (with the default closing day 25: 26 Sep - 25 Oct,
 * named "October"). Every date belongs to exactly one period.
 */
record ReadingPeriod(LocalDate start, LocalDate end) {

    static ReadingPeriod containing(LocalDate date, int closingDay) {
        LocalDate firstOfMonth = date.withDayOfMonth(1);
        LocalDate end = date.getDayOfMonth() <= closingDay
                ? firstOfMonth.withDayOfMonth(closingDay)
                : firstOfMonth.plusMonths(1).withDayOfMonth(closingDay);
        LocalDate start = end.minusMonths(1).withDayOfMonth(closingDay).plusDays(1);
        return new ReadingPeriod(start, end);
    }

    /** The first day of the following period. */
    LocalDate next() {
        return end.plusDays(1);
    }

    boolean contains(LocalDate date) {
        return !date.isBefore(start) && !date.isAfter(end);
    }
}
