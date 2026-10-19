package com.example.electronichome;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Who may do what: the roles, the links to accounts, and the order 401 -> 403 -> 404.
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "receipts.admin-token=it-admin-token")
class AccessControlIT {

    private static final String ACCOUNT = "000000000001";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

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

    private String owner;
    private String otherOwner;
    private String resident;
    private String admin;

    @BeforeEach
    void users() throws Exception {
        AuthSupport.createUser(jdbc, "it-owner", "OWNER", true, ACCOUNT);
        AuthSupport.createUser(jdbc, "it-other", "OWNER", true);
        AuthSupport.createUser(jdbc, "it-resident", "RESIDENT", true, ACCOUNT);
        AuthSupport.createUser(jdbc, "it-admin-ac", "ADMIN", true);
        owner = AuthSupport.bearer(port, "it-owner");
        otherOwner = AuthSupport.bearer(port, "it-other");
        resident = AuthSupport.bearer(port, "it-resident");
        admin = AuthSupport.bearer(port, "it-admin-ac");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM requests WHERE NOT is_test");
        jdbc.update("DELETE FROM users WHERE login LIKE 'it-%'");
        jdbc.update("DELETE FROM meter_readings WHERE reading_date = CURRENT_DATE");
        jdbc.update("DELETE FROM receipts");
    }

    private HttpResponse<String> call(String method, String path, String bearer, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", bearer);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        return call("GET", path, bearer, null);
    }

    private static final String READING = "{\"readings\":[{\"meter_number\":\"Тест 1\",\"value\":250.125}]}";

    @Test
    void theListOfAllAccountsIsForAdministratorsOnly() throws Exception {
        assertThat(get("/api/accounts", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/accounts", owner).statusCode()).isEqualTo(403);
        assertThat(get("/api/accounts", resident).statusCode()).isEqualTo(403);
        assertThat(get("/api/accounts", owner).body()).doesNotContain("000000000001");

        HttpResponse<String> response = get("/api/accounts", admin);

        assertThat(response.statusCode()).isEqualTo(200);
        tools.jackson.databind.JsonNode accounts = new tools.jackson.databind.json.JsonMapper().readTree(response.body()).get("accounts");
        assertThat(accounts.size()).isEqualTo(jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class));
        tools.jackson.databind.JsonNode first = accounts.get(0);
        assertThat(first.get("number").asString()).isEqualTo("000000000001");
        assertThat(first.get("address").asString()).isEqualTo("ул. Тестовая, д. Тест, кв. 1");
        assertThat(first.get("apartment").asString()).isEqualTo("1");
        assertThat(first.size()).isEqualTo(3);
        java.util.List<String> numbers = new java.util.ArrayList<>();
        accounts.forEach(account -> numbers.add(account.get("number").asString()));
        assertThat(numbers).isSorted();
    }

    @Test
    void requestsAreForOwnersAndAdministratorsOnly() throws Exception {
        assertThat(get("/api/requests", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/requests", resident).statusCode()).isEqualTo(403);
        assertThat(get("/api/requests", owner).statusCode()).isEqualTo(200);
        assertThat(get("/api/requests", admin).statusCode()).isEqualTo(200);
        assertThat(get("/api/requests/1/messages", resident).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/api/requests", resident, "{\"text\":\"т\"}").statusCode()).isEqualTo(403);
        assertThat(call("POST", "/api/requests", admin, "{\"text\":\"т\"}").statusCode()).isEqualTo(403);
    }

    @Test
    void ownerSeesOnlyTheirAccount() throws Exception {
        assertThat(get("/api/accounts/" + ACCOUNT, owner).statusCode()).isEqualTo(200);
        assertThat(get("/api/accounts/" + ACCOUNT, otherOwner).statusCode()).isEqualTo(403);
    }

    @Test
    void residentSeesTheirAccount() throws Exception {
        assertThat(get("/api/accounts/" + ACCOUNT, resident).statusCode()).isEqualTo(200);
    }

    @Test
    void administratorSeesAnyAccount() throws Exception {
        assertThat(get("/api/accounts/" + ACCOUNT, admin).statusCode()).isEqualTo(200);
    }

    @Test
    void foreignBodyOfAnAccountIsNotLeaked() throws Exception {
        HttpResponse<String> response = get("/api/accounts/" + ACCOUNT, otherOwner);

        assertThat(response.body()).doesNotContain("Тест");
    }

    @Test
    void missingAccountIsForbiddenForOwnerButNotFoundForAdministrator() throws Exception {
        assertThat(get("/api/accounts/999999999999", owner).statusCode()).isEqualTo(403);
        assertThat(get("/api/accounts/999999999999", resident).statusCode()).isEqualTo(403);
        assertThat(get("/api/accounts/999999999999", admin).statusCode()).isEqualTo(404);
    }

    @Test
    void expiredTokenIsUnauthorizedNotForbidden() throws Exception {
        String id = jdbc.queryForObject("SELECT id FROM users WHERE login = 'it-other'", String.class);
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("electronic-home").subject(id)
                .issuedAt(now.minusSeconds(7200)).expiresAt(now.minusSeconds(3600))
                .claim("roles", List.of("OWNER")).build();
        String expired = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        assertThat(get("/api/accounts/" + ACCOUNT, "Bearer " + expired).statusCode()).isEqualTo(401);
    }

    @Test
    void readingsCanBeSentByOwnerAndResidentOfTheAccountOnly() throws Exception {
        assertThat(call("POST", "/api/accounts/" + ACCOUNT + "/readings", otherOwner, READING).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/api/accounts/" + ACCOUNT + "/readings", null, READING).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meter_readings WHERE reading_date = CURRENT_DATE", Integer.class))
                .isZero();

        assertThat(call("POST", "/api/accounts/" + ACCOUNT + "/readings", resident, READING).statusCode()).isEqualTo(201);
    }

    @Test
    void receiptsAreForOwnerAndAdministratorOnly() throws Exception {
        String receipts = "/api/accounts/" + ACCOUNT + "/receipts";

        assertThat(get(receipts, null).statusCode()).isEqualTo(401);
        assertThat(get(receipts, resident).statusCode()).isEqualTo(403);
        assertThat(get(receipts + "/2026-09", resident).statusCode()).isEqualTo(403);
        assertThat(get(receipts, otherOwner).statusCode()).isEqualTo(403);
        assertThat(get(receipts, owner).statusCode()).isEqualTo(200);
        assertThat(get(receipts, admin).statusCode()).isEqualTo(200);
        assertThat(get(receipts + "/2026-09", owner).statusCode()).isEqualTo(404);
    }

    @Test
    void forbiddenAnswerDoesNotRevealRoles() throws Exception {
        HttpResponse<String> response = get("/api/accounts/" + ACCOUNT + "/receipts", resident);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).doesNotContain("ADMIN").doesNotContain("OWNER").doesNotContain("Exception");
    }

    private HttpResponse<String> publish(String month, String bearer, String adminToken) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/accounts/" + ACCOUNT + "/receipts/" + month))
                .header("Content-Type", "application/pdf")
                .PUT(HttpRequest.BodyPublishers.ofString("%PDF-1.4\n%%EOF\n", StandardCharsets.UTF_8));
        if (bearer != null) {
            request.header("Authorization", bearer);
        }
        if (adminToken != null) {
            request.header("X-Admin-Token", adminToken);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int receiptRows() {
        return jdbc.queryForObject("SELECT count(*) FROM receipts", Integer.class);
    }

    @Test
    void receiptsArePublishedWithTheAdminTokenAndNoJwt() throws Exception {
        assertThat(publish("2026-09", null, "it-admin-token").statusCode()).isEqualTo(201);
        assertThat(receiptRows()).isEqualTo(1);
    }

    @Test
    void administratorPublishesWithTheLoginTokenAndNoAdminToken() throws Exception {
        assertThat(publish("2026-09", admin, null).statusCode()).isEqualTo(201);
        assertThat(publish("2026-09", admin, null).statusCode()).isEqualTo(200);
        assertThat(receiptRows()).isEqualTo(1);
    }

    @Test
    void ownerAndResidentCannotPublishWithoutTheAdminToken() throws Exception {
        assertThat(publish("2026-09", owner, null).statusCode()).isEqualTo(403);
        assertThat(publish("2026-09", resident, null).statusCode()).isEqualTo(403);
        assertThat(publish("2026-09", otherOwner, "wrong").statusCode()).isEqualTo(403);
        assertThat(receiptRows()).isZero();
    }

    @Test
    void ownerWithTheAdminTokenStillCanPublishAsAScript() throws Exception {
        assertThat(publish("2026-09", owner, "it-admin-token").statusCode()).isEqualTo(201);
    }

    @Test
    void anonymousCallerWithoutTheTokenIsForbidden() throws Exception {
        assertThat(publish("2026-09", null, null).statusCode()).isEqualTo(403);
        assertThat(receiptRows()).isZero();
    }

    @Test
    void expiredLoginTokenIsUnauthorizedEvenWithTheRightAdminToken() throws Exception {
        String id = jdbc.queryForObject("SELECT id FROM users WHERE login = 'it-admin-ac'", String.class);
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("electronic-home").subject(id)
                .issuedAt(now.minusSeconds(7200)).expiresAt(now.minusSeconds(3600))
                .claim("roles", List.of("ADMIN")).build();
        String expired = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        assertThat(publish("2026-09", "Bearer " + expired, "it-admin-token").statusCode()).isEqualTo(401);
        assertThat(receiptRows()).isZero();
    }

    @Test
    void publishedReceiptCanBeDownloadedByTheOwner() throws Exception {
        publish("2026-09", admin, null);

        HttpResponse<String> response = get("/api/accounts/" + ACCOUNT + "/receipts/2026-09", owner);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).startsWith("%PDF-");
    }
}
