package com.example.electronichome.account;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A row of the {@code meter_readings} table; each reading may point to the previous one. */
@Entity
@Table(name = "meter_readings")
public class MeterReadingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meter_id", nullable = false)
    private WaterMeterEntity meter;

    @Column(nullable = false)
    private BigDecimal value;

    @Column(name = "reading_date", nullable = false)
    private LocalDate readingDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "previous_reading_id")
    private MeterReadingEntity previousReading;

    @Column(name = "is_test", nullable = false)
    private boolean test;

    protected MeterReadingEntity() {
    }

    public MeterReadingEntity(
            WaterMeterEntity meter, BigDecimal value, LocalDate readingDate, MeterReadingEntity previousReading, boolean test) {
        this.meter = meter;
        this.value = value;
        this.readingDate = readingDate;
        this.previousReading = previousReading;
        this.test = test;
    }

    public Long getId() {
        return id;
    }

    public BigDecimal getValue() {
        return value;
    }

    public LocalDate getReadingDate() {
        return readingDate;
    }

    public MeterReadingEntity getPreviousReading() {
        return previousReading;
    }
}
