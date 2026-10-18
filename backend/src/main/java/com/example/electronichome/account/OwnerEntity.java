package com.example.electronichome.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Read-only view of the {@code owners} table. */
@Entity
@Table(name = "owners")
public class OwnerEntity {

    @Id
    private Long id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    protected OwnerEntity() {
    }

    public Long getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }
}
