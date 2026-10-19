package com.example.electronichome.auth;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads the user accounts and their links to the personal accounts. */
@Repository
public class UserDirectory {

    /** A user account as stored; the hash never leaves the {@code auth} package. */
    record User(long id, String login, String passwordHash, Role role, boolean enabled) {
    }

    private final JdbcClient jdbc;

    UserDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<User> findByLogin(String login) {
        return jdbc.sql("SELECT id, login, password_hash, role, enabled FROM users WHERE lower(login) = lower(:login)")
                .param("login", login)
                .query((rs, row) -> toUser(rs))
                .optional();
    }

    Optional<User> findById(long id) {
        return jdbc.sql("SELECT id, login, password_hash, role, enabled FROM users WHERE id = :id")
                .param("id", id)
                .query((rs, row) -> toUser(rs))
                .optional();
    }

    /** The accounts an administrator sees: all of them. */
    List<String> allAccountNumbers() {
        return jdbc.sql("SELECT number FROM accounts ORDER BY number").query(String.class).list();
    }

    List<String> accountNumbersOf(long userId) {
        return jdbc.sql("""
                        SELECT a.number FROM user_accounts ua JOIN accounts a ON a.id = ua.account_id
                        WHERE ua.user_id = :userId ORDER BY a.number""")
                .param("userId", userId)
                .query(String.class)
                .list();
    }

    boolean isLinked(long userId, String accountNumber) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM user_accounts ua JOIN accounts a ON a.id = ua.account_id
                        WHERE ua.user_id = :userId AND a.number = :number)""")
                .param("userId", userId)
                .param("number", accountNumber)
                .query(Boolean.class)
                .single();
    }

    private static User toUser(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new User(
                rs.getLong("id"),
                rs.getString("login"),
                rs.getString("password_hash"),
                Role.valueOf(rs.getString("role")),
                rs.getBoolean("enabled"));
    }
}
