package com.example.electronichome.account;

import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Read-only view of the {@code accounts} table with its address and co-owners. */
@Entity
@Table(name = "accounts")
public class AccountEntity {

    @Id
    private Long id;

    @Column(nullable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "address_id", nullable = false)
    private AddressEntity address;

    @Column(name = "is_test", nullable = false)
    private boolean test;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "account_owners",
            joinColumns = @JoinColumn(name = "account_id"),
            inverseJoinColumns = @JoinColumn(name = "owner_id"))
    private Set<OwnerEntity> owners = new HashSet<>();

    protected AccountEntity() {
    }

    public Long getId() {
        return id;
    }

    public String getNumber() {
        return number;
    }

    public boolean isTest() {
        return test;
    }

    public AddressEntity getAddress() {
        return address;
    }

    public Set<OwnerEntity> getOwners() {
        return owners;
    }
}
