package com.example.electronichome.account;

import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/** Read-only view of the {@code water_meters} table. */
@Entity
@Table(name = "water_meters")
public class WaterMeterEntity {

    @Id
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "address_id", nullable = false)
    private AddressEntity address;

    /** {@code HOT} or {@code COLD}, as stored. */
    @Column(name = "water_type", nullable = false)
    private String waterType;

    @Column(nullable = false)
    private String number;

    @OneToMany(mappedBy = "meter")
    private Set<MeterReadingEntity> readings = new HashSet<>();

    protected WaterMeterEntity() {
    }

    public Long getId() {
        return id;
    }

    public String getWaterType() {
        return waterType;
    }

    public String getNumber() {
        return number;
    }

    public Set<MeterReadingEntity> getReadings() {
        return readings;
    }
}
