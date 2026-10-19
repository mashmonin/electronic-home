package com.example.electronichome;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Login, the token and /api/auth/me on a real server and database.
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthApiIT {

    private static final String DEV_PASSWORD = "Owner-dev-123";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    // the health includes the storage, and this test asks for the health
    @org.springframework.test.context.DynamicPropertySource
    static void storage(org.springframework.test.context.DynamicPropertyRegistry registry) {
        SeaweedFsTestContainer.register(registry);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtEncoder encoder;

    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void removeUsers() {
        jdbc.update("DELETE FROM users WHERE login LIKE 'it-%'");
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> login(String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> login(String login, String password) throws Exception {
        return login("{\"login\":\"" + login + "\",\"password\":\"" + password + "\"}");
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (bearer != null) {
            request.header("Authorization", bearer);
        }
        return send(request);
    }

    private JsonNode claimsOf(String token) throws Exception {
        return json.readTree(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8));
    }

    private String tokenWith(JwtEncoder using, String subject, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("electronic-home")
                .subject(subject)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("roles", List.of("ADMIN"))
                .build();
        return using.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Test
    void testUserSignsIn() throws Exception {
        HttpResponse<String> response = login("owner", DEV_PASSWORD);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("access_token").asString()).isNotBlank();
        assertThat(body.get("token_type").asString()).isEqualTo("Bearer");
        assertThat(body.get("expires_in").asLong()).isEqualTo(8 * 3600);
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    void loginIgnoresCase() throws Exception {
        assertThat(login("OWNER", DEV_PASSWORD).statusCode()).isEqualTo(200);
    }

    @Test
    void wrongPasswordUnknownLoginAndDisabledUserLookTheSame() throws Exception {
        AuthSupport.createUser(jdbc, "it-off", "OWNER", false);

        HttpResponse<String> wrongPassword = login("owner", "wrong");
        HttpResponse<String> unknown = login("nobody", DEV_PASSWORD);
        HttpResponse<String> disabled = login("it-off", AuthSupport.PASSWORD);

        assertThat(wrongPassword.statusCode()).isEqualTo(401);
        assertThat(unknown.statusCode()).isEqualTo(401);
        assertThat(disabled.statusCode()).isEqualTo(401);
        assertThat(withoutTime(unknown.body())).isEqualTo(withoutTime(wrongPassword.body()));
        assertThat(withoutTime(disabled.body())).isEqualTo(withoutTime(wrongPassword.body()));
    }

    private static String withoutTime(String body) {
        return body.replaceAll("\"timestamp\":\"[^\"]*\",?", "");
    }

    @Test
    void incompleteRequestIsRejected() throws Exception {
        assertThat(login("{\"login\":\"owner\"}").statusCode()).isEqualTo(400);
        assertThat(login("{\"login\":\"\",\"password\":\"x\"}").statusCode()).isEqualTo(400);
        assertThat(login("{\"login\":\"owner\",\"password\":\"  \"}").statusCode()).isEqualTo(400);
    }

    @Test
    void tokenHasStandardClaimsAndTheRole() throws Exception {
        String token = json.readTree(login("owner", DEV_PASSWORD).body()).get("access_token").asString();

        JsonNode claims = claimsOf(token);

        Long id = jdbc.queryForObject("SELECT id FROM users WHERE login = 'owner'", Long.class);
        assertThat(claims.get("iss").asString()).isEqualTo("electronic-home");
        assertThat(claims.get("sub").asString()).isEqualTo(String.valueOf(id));
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(8 * 3600);
        assertThat(claims.get("roles")).hasSize(1);
        assertThat(claims.get("roles").get(0).asString()).isEqualTo("OWNER");
        assertThat(claims.toString()).doesNotContain(DEV_PASSWORD).doesNotContain("$2a$");
    }

    @Test
    void meReturnsRoleAndAccountsOfTheOwner() throws Exception {
        HttpResponse<String> response = get("/api/auth/me", AuthSupport.bearer(port, "owner", DEV_PASSWORD));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("login").asString()).isEqualTo("owner");
        assertThat(body.get("role").asString()).isEqualTo("OWNER");
        assertThat(body.get("accounts")).hasSize(1);
        assertThat(body.get("accounts").get(0).asString()).isEqualTo("000000000001");
        assertThat(body.size()).isEqualTo(3);
    }

    @Test
    void meReturnsAllAccountsToAnAdministrator() throws Exception {
        AuthSupport.createUser(jdbc, "it-admin-me", "ADMIN", true);
        int accounts = jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class);

        JsonNode body = json.readTree(get("/api/auth/me", AuthSupport.bearer(port, "it-admin-me")).body());

        assertThat(body.get("role").asString()).isEqualTo("ADMIN");
        assertThat(body.get("accounts")).hasSize(accounts);
        assertThat(body.get("accounts").get(0).asString()).isEqualTo("000000000001");
    }

    @Test
    void testAdministratorSignsInAndSeesAllAccounts() throws Exception {
        int accounts = jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class);

        JsonNode body = json.readTree(get("/api/auth/me", AuthSupport.bearer(port, "admin", "Admin-dev-123")).body());

        assertThat(body.get("role").asString()).isEqualTo("ADMIN");
        assertThat(body.get("accounts")).hasSize(accounts);
    }

    @Test
    void meNeedsAToken() throws Exception {
        HttpResponse<String> response = get("/api/auth/me", null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate").orElseThrow()).startsWith("Bearer");
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        AuthSupport.createUser(jdbc, "it-exp", "ADMIN", true);
        String id = jdbc.queryForObject("SELECT id FROM users WHERE login = 'it-exp'", String.class);
        Instant now = Instant.now();

        String expired = tokenWith(encoder, id, now.minusSeconds(7200), now.minusSeconds(3600));
        String valid = tokenWith(encoder, id, now, now.plusSeconds(3600));

        assertThat(get("/api/auth/me", "Bearer " + expired).statusCode()).isEqualTo(401);
        assertThat(get("/api/auth/me", "Bearer " + valid).statusCode()).isEqualTo(200);
    }

    @Test
    void forgedAndTamperedTokensAreRejected() throws Exception {
        AuthSupport.createUser(jdbc, "it-forged", "ADMIN", true);
        String id = jdbc.queryForObject("SELECT id FROM users WHERE login = 'it-forged'", String.class);
        Instant now = Instant.now();
        JwtEncoder other = new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec("another-secret-key-another-secret-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        String foreign = tokenWith(other, id, now, now.plusSeconds(3600));
        String own = tokenWith(encoder, id, now, now.plusSeconds(3600));
        String[] parts = own.split("\\.");
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(claimsOf(own).toString().replace("ADMIN", "OWNER").getBytes(StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + payload + "." + parts[2];

        assertThat(get("/api/auth/me", "Bearer " + foreign).statusCode()).isEqualTo(401);
        assertThat(get("/api/auth/me", "Bearer " + tampered).statusCode()).isEqualTo(401);
        assertThat(get("/api/auth/me", "Bearer garbage").statusCode()).isEqualTo(401);
    }

    @Test
    void disablingAUserStopsTheirTokenAtOnce() throws Exception {
        AuthSupport.createUser(jdbc, "it-later-off", "OWNER", true, "000000000001");
        String bearer = AuthSupport.bearer(port, "it-later-off");
        assertThat(get("/api/auth/me", bearer).statusCode()).isEqualTo(200);

        jdbc.update("UPDATE users SET enabled = FALSE WHERE login = 'it-later-off'");

        assertThat(get("/api/auth/me", bearer).statusCode()).isEqualTo(401);
    }

    @Test
    void healthNeedsNoToken() throws Exception {
        assertThat(get("/api/actuator/health", null).statusCode()).isEqualTo(200);
    }

    @Test
    void accountApiNeedsAToken() throws Exception {
        HttpResponse<String> response = get("/api/accounts/000000000001", null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).doesNotContain("000000000001");
    }
}
