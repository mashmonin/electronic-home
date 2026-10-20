package com.example.electronichome.request;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Gives the requests their numbers: {@code X-ддммгг-NNNN}, where {@code X} is the Cyrillic letter of the kind: {@code О} ("обращение")
 * or {@code П} ("проверка"),
 * {@code ддммгг} is the date of creation and {@code NNNN} is the number of the request among all of them (it does not
 * start again every day). The last part comes from a sequence of the database, so that two requests created at once
 * never get the same number; a creation that fails leaves a gap, a number is never used twice.
 */
@Component
class RequestNumbers {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("ddMMyy");

    private final JdbcClient jdbc;
    private final Clock clock;

    RequestNumbers(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    String next() {
        return next(RequestKind.GENERAL);
    }

    /** The next number; the letter depends on the kind, the sequence is the same for all of them. */
    String next(RequestKind kind) {
        long sequence = jdbc.sql("SELECT nextval('request_number_seq')").query(Long.class).single();
        return format(kind, LocalDate.now(clock), sequence);
    }

    static String format(LocalDate date, long sequence) {
        return format(RequestKind.GENERAL, date, sequence);
    }

    static String format(RequestKind kind, LocalDate date, long sequence) {
        return "%s-%s-%04d".formatted(kind.letter(), DATE.format(date), sequence);
    }
}
