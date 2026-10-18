package com.example.electronichome.account;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

interface AccountRepository extends Repository<AccountEntity, Long> {

    @EntityGraph(attributePaths = {"address", "owners"})
    Optional<AccountEntity> findByNumber(String number);
}
