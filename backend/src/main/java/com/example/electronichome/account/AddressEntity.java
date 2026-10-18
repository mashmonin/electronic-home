package com.example.electronichome.account;

import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/** Read-only view of the {@code addresses} table: an address is a single apartment. */
@Entity
@Table(name = "addresses")
public class AddressEntity {

    @Id
    private Long id;

    @Column(nullable = false)
    private String street;

    @Column(nullable = false)
    private String house;

    @Column(nullable = false)
    private String apartment;

    @OneToMany(mappedBy = "address")
    private Set<WaterMeterEntity> meters = new HashSet<>();

    protected AddressEntity() {
    }

    public String getStreet() {
        return street;
    }

    public String getHouse() {
        return house;
    }

    public String getApartment() {
        return apartment;
    }

    public Set<WaterMeterEntity> getMeters() {
        return meters;
    }
}
