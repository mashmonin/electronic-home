package com.example.electronichome;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import tools.jackson.databind.json.JsonMapper;

/** Creates users and signs them in through the real login endpoint, for the integration tests. */
final class AuthSupport {

    static final String PASSWORD = "Test-pass-123";
    private static final String HASH = new BCryptPasswordEncoder().encode(PASSWORD);
    // A port may be reused by another application context (and so another database): remember which one it was.
    private record Admin(int dataSource, String bearer) {
    }

    private static final Map<Integer, Admin> ADMINS = new ConcurrentHashMap<>();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AuthSupport() {
    }

    /** Adds a user (replacing one with the same login) linked to the given personal accounts. */
    static void createUser(JdbcTemplate jdbc, String login, String role, boolean enabled, String... accountNumbers) {
        deleteUser(jdbc, login);
        jdbc.update("INSERT INTO users (login, password_hash, role, enabled, is_test) VALUES (?, ?, ?, ?, TRUE)",
                login, HASH, role, enabled);
        for (String number : accountNumbers) {
            jdbc.update("""
                    INSERT INTO user_accounts (user_id, account_id, is_test)
                    SELECT u.id, a.id, TRUE FROM users u, accounts a WHERE u.login = ? AND a.number = ?""", login, number);
        }
    }

    /** Deletes the user together with his requests (a user with requests cannot be deleted otherwise). */
    static void deleteUser(JdbcTemplate jdbc, String login) {
        jdbc.update("DELETE FROM request_messages WHERE author_id IN (SELECT id FROM users WHERE login = ?)", login);
        jdbc.update("DELETE FROM requests WHERE author_id IN (SELECT id FROM users WHERE login = ?)", login);
        jdbc.update("DELETE FROM users WHERE login = ?", login);
    }

    /** Logs in and returns the value of the {@code Authorization} header. */
    static String bearer(int port, String login, String password) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"login\":\"" + login + "\",\"password\":\"" + password + "\"}"))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Login of " + login + " failed: " + response.statusCode());
        }
        return "Bearer " + JSON.readTree(response.body()).get("access_token").asString();
    }

    static String bearer(int port, String login) throws Exception {
        return bearer(port, login, PASSWORD);
    }

    /** An administrator for the tests that touch any account; remembered per server port. */
    static String adminBearer(JdbcTemplate jdbc, int port) throws Exception {
        int dataSource = System.identityHashCode(jdbc.getDataSource());
        Admin known = ADMINS.get(port);
        if (known != null && known.dataSource() == dataSource) {
            return known.bearer();
        }
        createUser(jdbc, "it-admin", "ADMIN", true);
        String bearer = bearer(port, "it-admin");
        ADMINS.put(port, new Admin(dataSource, bearer));
        return bearer;
    }

    static String adminBearer(int port) {
        Admin known = ADMINS.get(port);
        if (known == null) {
            throw new IllegalStateException("Call adminBearer(jdbc, port) first");
        }
        return known.bearer();
    }
}
