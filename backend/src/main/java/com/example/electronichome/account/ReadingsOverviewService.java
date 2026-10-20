package com.example.electronichome.account;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The readings of all the apartments for the administrator: the months (periods of the readings, named after the month
 * they end in) and, for one month, every apartment that has meters with the reading of each meter or none.
 */
@Service
class ReadingsOverviewService {

    private static final Pattern MONTH = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

    private final JdbcClient jdbc;
    private final ReadingPeriodProperties properties;
    private final Clock clock;

    ReadingsOverviewService(JdbcClient jdbc, ReadingPeriodProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    /** The months that have a reading and the current one, newest first. */
    @Transactional(readOnly = true)
    ReadingsOverviewResponses.Periods periods() {
        int closing = properties.closingDay();
        TreeSet<YearMonth> months = new TreeSet<>(Comparator.reverseOrder());
        jdbc.sql("""
                        SELECT DISTINCT CASE WHEN extract(day FROM reading_date) <= :closing
                                             THEN date_trunc('month', reading_date)::date
                                             ELSE (date_trunc('month', reading_date) + interval '1 month')::date END AS month
                        FROM meter_readings""")
                .param("closing", closing)
                .query((rs, row) -> YearMonth.from(rs.getDate("month").toLocalDate()))
                .list()
                .forEach(months::add);
        months.add(currentMonth());
        return new ReadingsOverviewResponses.Periods(months.stream().map(this::period).toList());
    }

    @Transactional(readOnly = true)
    ReadingsOverviewResponses.Month month(String raw) {
        if (raw == null || !MONTH.matcher(raw).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The month must be YYYY-MM");
        }
        YearMonth month = YearMonth.parse(raw);
        if (month.isAfter(currentMonth())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The month is later than the current period");
        }
        ReadingsOverviewResponses.Period period = period(month);
        // the last reading of each meter in the period, with the reading before it; the meters of all the apartments
        List<Row> rows = jdbc.sql("""
                        SELECT a.number AS account_number, ad.street, ad.house, ad.apartment,
                               m.number AS meter_number, m.water_type,
                               r.value, r.reading_date, p.value AS previous_value
                        FROM accounts a
                        JOIN addresses ad ON ad.id = a.address_id
                        JOIN water_meters m ON m.address_id = ad.id
                        LEFT JOIN LATERAL (SELECT x.value, x.reading_date, x.previous_reading_id
                                           FROM meter_readings x
                                           WHERE x.meter_id = m.id AND x.reading_date BETWEEN :start AND :end
                                           ORDER BY x.reading_date DESC, x.id DESC LIMIT 1) r ON TRUE
                        LEFT JOIN meter_readings p ON p.id = r.previous_reading_id
                        ORDER BY NULLIF(regexp_replace(ad.apartment, '\\D', '', 'g'), '')::int NULLS LAST,
                                 ad.apartment, a.number, m.number""")
                .param("start", period.start())
                .param("end", period.end())
                .query((rs, row) -> new Row(
                        rs.getString("account_number"),
                        "%s, д. %s, кв. %s".formatted(rs.getString("street"), rs.getString("house"), rs.getString("apartment")),
                        rs.getString("apartment"),
                        rs.getString("meter_number"),
                        rs.getString("water_type"),
                        rs.getBigDecimal("value"),
                        rs.getObject("reading_date", LocalDate.class),
                        rs.getBigDecimal("previous_value")))
                .list();

        Map<String, List<Row>> byAccount = new LinkedHashMap<>();
        rows.forEach(row -> byAccount.computeIfAbsent(row.accountNumber(), key -> new ArrayList<>()).add(row));
        List<ReadingsOverviewResponses.Apartment> apartments = byAccount.values().stream().map(this::apartment).toList();
        return new ReadingsOverviewResponses.Month(period.month(), period.start(), period.end(), apartments);
    }

    private record Row(
            String accountNumber, String address, String apartment, String meterNumber, String waterType,
            BigDecimal value, LocalDate readingDate, BigDecimal previousValue) {
    }

    private ReadingsOverviewResponses.Apartment apartment(List<Row> rows) {
        Row first = rows.get(0);
        List<ReadingsOverviewResponses.Meter> meters = rows.stream().map(row -> new ReadingsOverviewResponses.Meter(
                row.meterNumber(),
                row.waterType(),
                row.value() == null ? null : new ReadingsOverviewResponses.Reading(
                        row.value(),
                        row.readingDate(),
                        row.previousValue(),
                        row.previousValue() == null ? null : row.value().subtract(row.previousValue())))).toList();
        int submitted = (int) meters.stream().filter(meter -> meter.reading() != null).count();
        return new ReadingsOverviewResponses.Apartment(
                first.accountNumber(), first.apartment(), first.address(), submitted, meters.size(), meters);
    }

    private YearMonth currentMonth() {
        return YearMonth.from(ReadingPeriod.containing(LocalDate.now(clock), properties.closingDay()).end());
    }

    // The period that ends in the month: from the day after the closing day of the previous month to the closing day of this one.
    private ReadingsOverviewResponses.Period period(YearMonth month) {
        LocalDate end = month.atDay(properties.closingDay());
        LocalDate start = end.minusMonths(1).plusDays(1);
        return new ReadingsOverviewResponses.Period(month.toString(), start, end);
    }
}
