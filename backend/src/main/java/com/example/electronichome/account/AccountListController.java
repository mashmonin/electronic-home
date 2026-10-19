package com.example.electronichome.account;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The list of all accounts, for an administrator who chooses the apartment to publish a receipt for. */
@RestController
@RequestMapping("/api/accounts")
class AccountListController {

    private final JdbcClient jdbc;

    AccountListController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    AccountListResponse list() {
        return new AccountListResponse(jdbc.sql("""
                        SELECT a.number, ad.street, ad.house, ad.apartment
                        FROM accounts a JOIN addresses ad ON ad.id = a.address_id
                        ORDER BY a.number""")
                .query((rs, row) -> new AccountListResponse.Item(
                        rs.getString("number"),
                        "%s, д. %s, кв. %s".formatted(rs.getString("street"), rs.getString("house"), rs.getString("apartment")),
                        rs.getString("apartment")))
                .list());
    }
}
