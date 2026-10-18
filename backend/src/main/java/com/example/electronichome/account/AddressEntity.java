package com.example.electronichome.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
}
