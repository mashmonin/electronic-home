package com.example.electronichome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HousingSchemaIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE account_owners, address_owners, accounts, owners, addresses RESTART IDENTITY CASCADE");
    }

    private long address(String apartment) {
        return jdbc.queryForObject(
                "INSERT INTO addresses (street, house, apartment) VALUES ('ул. Садовая', '12', ?) RETURNING id",
                Long.class, apartment);
    }

    private long account(String number, Long addressId) {
        return jdbc.queryForObject(
                "INSERT INTO accounts (number, address_id) VALUES (?, ?) RETURNING id",
                Long.class, number, addressId);
    }

    private long owner(String fullName) {
        return jdbc.queryForObject(
                "INSERT INTO owners (full_name) VALUES (?) RETURNING id", Long.class, fullName);
    }

    private void linkAddress(long addressId, long ownerId) {
        jdbc.update("INSERT INTO address_owners (address_id, owner_id) VALUES (?, ?)", addressId, ownerId);
    }

    private void linkAccount(long accountId, long ownerId) {
        jdbc.update("INSERT INTO account_owners (account_id, owner_id) VALUES (?, ?)", accountId, ownerId);
    }

    @Test
    void migrationCreatesAllTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);

        assertThat(tables).contains("addresses", "accounts", "owners", "address_owners", "account_owners");
    }

    @Test
    void recordsAreNotTestByDefault() {
        address("47");
        owner("Петрова Анна");

        assertThat(jdbc.queryForObject("SELECT is_test FROM addresses", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT is_test FROM owners", Boolean.class)).isFalse();
    }

    @Test
    void addressGetsGeneratedIdAndRejectsDuplicate() {
        assertThat(address("47")).isPositive();

        assertThatThrownBy(() -> address("47")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(address("48")).isPositive();
    }

    @Test
    void accountNumberIsUniqueAndKeepsLeadingZeros() {
        long address = address("47");
        account("0004521873", address);

        assertThatThrownBy(() -> account("0004521873", address)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT number FROM accounts", String.class)).isEqualTo("0004521873");
    }

    @Test
    void addressCanHaveSeveralAccounts() {
        long address = address("47");
        account("0004521873", address);
        account("0004521874", address);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE address_id = ?", Integer.class, address))
                .isEqualTo(2);
    }

    @Test
    void accountRequiresExistingAddress() {
        assertThatThrownBy(() -> account("1", null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> account("2", 999L)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ownersWithSameNameAreSeparateRecords() {
        owner("Иванов Иван Иванович");
        owner("Иванов Иван Иванович");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM owners", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO owners (full_name) VALUES (NULL)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void addressAndOwnerRelationIsManyToMany() {
        long first = address("47");
        long second = address("48");
        long anna = owner("Петрова Анна");
        long boris = owner("Сидоров Борис");

        linkAddress(first, anna);
        linkAddress(first, boris);
        linkAddress(second, anna);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM address_owners WHERE address_id = ?", Integer.class, first))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM address_owners WHERE owner_id = ?", Integer.class, anna))
                .isEqualTo(2);
        assertThatThrownBy(() -> linkAddress(first, anna)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void accountCanHaveSeveralCoOwners() {
        long account = account("0004521873", address("47"));
        long anna = owner("Петрова Анна");
        long boris = owner("Сидоров Борис");

        linkAccount(account, anna);
        linkAccount(account, boris);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_owners WHERE account_id = ?", Integer.class, account))
                .isEqualTo(2);
        assertThatThrownBy(() -> linkAccount(account, anna)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ownerCanBeCoOwnerOfSeveralAccounts() {
        long address = address("47");
        long anna = owner("Петрова Анна");

        linkAccount(account("0004521873", address), anna);
        linkAccount(account("0004521874", address), anna);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_owners WHERE owner_id = ?", Integer.class, anna))
                .isEqualTo(2);
    }

    @Test
    void linksToMissingRecordsAreRejected() {
        long address = address("47");
        long account = account("0004521873", address);
        long anna = owner("Петрова Анна");

        assertThatThrownBy(() -> linkAddress(address, 999L)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> linkAccount(account, 999L)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> linkAddress(999L, anna)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingRecordsWithLinksIsRejected() {
        long address = address("47");
        long account = account("0004521873", address);
        long anna = owner("Петрова Анна");
        linkAddress(address, anna);
        linkAccount(account, anna);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM addresses WHERE id = ?", address))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM accounts WHERE id = ?", account))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM owners WHERE id = ?", anna))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM addresses", Integer.class)).isEqualTo(1);
    }
}
