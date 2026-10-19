package com.example.electronichome;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// The order of the list (those that wait for an answer first, the longest waiting on top) and the search by the number.
// The conversations are written straight into the database, so that the times of the messages can be chosen.
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestListIT {

    private static final String ACCOUNT_1 = "000000000001";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();
    private String owner1;
    private String owner2;
    private String admin;

    @BeforeEach
    void users() throws Exception {
        jdbc.update("DELETE FROM requests WHERE is_test");
        jdbc.update("INSERT INTO addresses (street, house, apartment, is_test) VALUES ('ул. Тестовая', 'Тест', '97', TRUE)");
        jdbc.update("INSERT INTO accounts (number, address_id, is_test) SELECT '000000000097', id, TRUE FROM addresses WHERE apartment = '97'");
        AuthSupport.createUser(jdbc, "it-owner1", "OWNER", true, ACCOUNT_1);
        AuthSupport.createUser(jdbc, "it-owner2", "OWNER", true, "000000000097");
        AuthSupport.createUser(jdbc, "it-admin-ls", "ADMIN", true);
        owner1 = AuthSupport.bearer(port, "it-owner1");
        owner2 = AuthSupport.bearer(port, "it-owner2");
        admin = AuthSupport.bearer(port, "it-admin-ls");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM requests");
        jdbc.update("DELETE FROM users WHERE login LIKE 'it-%'");
        jdbc.update("DELETE FROM accounts WHERE number = '000000000097'");
        jdbc.update("DELETE FROM addresses WHERE apartment = '97'");
    }

    /** One message of a conversation: who wrote it and how many minutes ago. */
    private record Said(String who, int minutesAgo) {
    }

    private static Said owner(int minutesAgo) {
        return new Said("OWNER", minutesAgo);
    }

    private static Said administrator(int minutesAgo) {
        return new Said("ADMIN", minutesAgo);
    }

    private void conversation(String number, String ownerLogin, Said... said) {
        long id = jdbc.queryForObject("""
                INSERT INTO requests (account_id, author_id, number)
                SELECT a.id, u.id, ? FROM users u JOIN user_accounts ua ON ua.user_id = u.id JOIN accounts a ON a.id = ua.account_id
                WHERE u.login = ? RETURNING id""", Long.class, number, ownerLogin);
        for (Said message : said) {
            String login = message.who().equals("ADMIN") ? "it-admin-ls" : ownerLogin;
            jdbc.update("""
                    INSERT INTO request_messages (request_id, author_id, body, created_at)
                    SELECT ?, id, ?, now() - make_interval(mins => ?) FROM users WHERE login = ?""",
                    id, message.who() + " " + message.minutesAgo(), message.minutesAgo(), login);
        }
        jdbc.update("UPDATE requests SET last_message_at = (SELECT max(created_at) FROM request_messages WHERE request_id = ?) WHERE id = ?", id, id);
    }

    private JsonNode list(String bearer, String query) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/requests" + query))
                .header("Authorization", bearer).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).get("requests");
    }

    private List<String> numbers(String bearer, String query) throws Exception {
        List<String> numbers = new ArrayList<>();
        list(bearer, query).forEach(item -> numbers.add(item.get("number").asString()));
        return numbers;
    }

    private String q(String fragment) {
        return "?q=" + URLEncoder.encode(fragment, StandardCharsets.UTF_8);
    }

    // ---- the order

    @Test
    void thoseWithoutAnswerAreOnTopTheLongestWaitingFirst() throws Exception {
        conversation("А", "it-owner1", owner(60));
        conversation("Б", "it-owner1", owner(150), administrator(120));
        conversation("В", "it-owner1", owner(240));

        assertThat(numbers(admin, "")).containsExactly("В", "А", "Б");
        assertThat(numbers(owner1, "")).containsExactly("В", "А", "Б");
    }

    @Test
    void answeredRequestsGoByTheLastMessageNewestFirst() throws Exception {
        conversation("А", "it-owner1", owner(200), administrator(90));
        conversation("Б", "it-owner1", owner(100), administrator(30));
        conversation("В", "it-owner1", owner(400), administrator(60));

        assertThat(numbers(admin, "")).containsExactly("Б", "В", "А");
    }

    @Test
    void anotherMessageOfTheOwnerDoesNotResetTheWaiting() throws Exception {
        conversation("Д", "it-owner1", owner(300), owner(10));
        conversation("Е", "it-owner1", owner(200));

        assertThat(numbers(admin, "")).containsExactly("Д", "Е");
    }

    @Test
    void theWaitingCountsFromTheFirstMessageAfterTheLastAnswer() throws Exception {
        conversation("Ж", "it-owner1", owner(400), administrator(300), owner(5));
        conversation("З", "it-owner1", owner(100));

        assertThat(numbers(admin, "")).containsExactly("З", "Ж");
    }

    @Test
    void theAwaitingFlagFollowsTheLastMessage() throws Exception {
        conversation("А", "it-owner1", owner(60));
        conversation("Б", "it-owner1", owner(150), administrator(120));
        conversation("В", "it-owner1", owner(300), administrator(200), owner(100));

        JsonNode items = list(admin, "");
        assertThat(items.get(0).get("number").asString()).isEqualTo("В");
        assertThat(items.get(0).get("awaiting_reply").asBoolean()).isTrue();
        assertThat(items.get(1).get("awaiting_reply").asBoolean()).isTrue();
        assertThat(items.get(2).get("number").asString()).isEqualTo("Б");
        assertThat(items.get(2).get("awaiting_reply").asBoolean()).isFalse();
    }

    @Test
    void anAnswerTakesARequestOutOfTheTopGroupAndANewQuestionBringsItBack() throws Exception {
        conversation("А", "it-owner1", owner(60));
        conversation("Б", "it-owner1", owner(30));
        assertThat(numbers(admin, "")).containsExactly("А", "Б");
        long first = jdbc.queryForObject("SELECT id FROM requests WHERE number = 'А'", Long.class);

        post(admin, first, "Ответ администратора");
        assertThat(numbers(admin, "")).containsExactly("Б", "А");

        post(owner1, first, "Новый вопрос");
        assertThat(numbers(admin, "")).containsExactly("Б", "А");
        jdbc.update("UPDATE request_messages SET created_at = now() - interval '10 minutes' WHERE request_id = ? AND body = 'Новый вопрос'", first);
        jdbc.update("UPDATE requests SET last_message_at = now() - interval '10 minutes' WHERE id = ?", first);
        assertThat(numbers(admin, "")).containsExactly("Б", "А");
        assertThat(list(admin, "").get(1).get("awaiting_reply").asBoolean()).isTrue();
    }

    private void post(String bearer, long id, String text) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/requests/" + id + "/messages"))
                .header("Authorization", bearer).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.createObjectNode().put("text", text).toString())).build();
        assertThat(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(201);
    }

    @Test
    void theLimitOf200IsAppliedToTheOrderedList() throws Exception {
        for (int i = 0; i < 205; i++) {
            conversation("Н-" + i, "it-owner1", owner(1000 - i));
        }
        conversation("Самая-давняя", "it-owner1", owner(5000));

        List<String> numbers = numbers(admin, "");

        assertThat(numbers).hasSize(200);
        assertThat(numbers.get(0)).isEqualTo("Самая-давняя");
    }

    // ---- the search

    private void numbersForSearch() {
        conversation("О-191026-0001", "it-owner1", owner(60));
        conversation("О-201026-0002", "it-owner1", owner(50));
        conversation("О-051125-0123", "it-owner2", owner(40));
    }

    @Test
    void aFragmentIsFoundAnywhereInTheNumber() throws Exception {
        numbersForSearch();

        assertThat(numbers(admin, q("1910"))).containsExactly("О-191026-0001");
        assertThat(numbers(admin, q("0002"))).containsExactly("О-201026-0002");
        assertThat(numbers(admin, q("191026-00"))).containsExactly("О-191026-0001");
        assertThat(numbers(admin, q("О-191026-0001"))).containsExactly("О-191026-0001");
        assertThat(numbers(admin, q("1026"))).containsExactly("О-191026-0001", "О-201026-0002");
        assertThat(numbers(admin, q("123"))).containsExactly("О-051125-0123");
    }

    @Test
    void theCaseAndTheLatinOAreNotImportant() throws Exception {
        numbersForSearch();

        assertThat(numbers(admin, q("O-1910"))).containsExactly("О-191026-0001");
        assertThat(numbers(admin, q("o-1910"))).containsExactly("О-191026-0001");
        assertThat(numbers(admin, q("о-2010"))).containsExactly("О-201026-0002");
    }

    @Test
    void nothingFoundIsAnEmptyListAndAnEmptyQueryIsNoSearch() throws Exception {
        numbersForSearch();

        assertThat(numbers(admin, q("7777"))).isEmpty();
        assertThat(numbers(admin, q(""))).hasSize(3);
        assertThat(numbers(admin, q("   "))).hasSize(3);
        assertThat(numbers(admin, "")).hasSize(3);
    }

    @Test
    void spacesAtTheEdgesAreDroppedAndWildcardsAreOrdinaryCharacters() throws Exception {
        numbersForSearch();

        assertThat(numbers(admin, q("  0002  "))).containsExactly("О-201026-0002");
        assertThat(numbers(admin, q("%"))).isEmpty();
        assertThat(numbers(admin, q("_"))).isEmpty();
        assertThat(numbers(admin, q("О%0001"))).isEmpty();
    }

    @Test
    void anOwnerFindsOnlyOwnRequests() throws Exception {
        numbersForSearch();

        assertThat(numbers(owner1, q("0123"))).isEmpty();
        assertThat(numbers(owner2, q("0123"))).containsExactly("О-051125-0123");
        assertThat(numbers(owner1, q("1026"))).containsExactly("О-191026-0001", "О-201026-0002");
    }

    @Test
    void theFoundRequestsKeepTheOrderOfTheList() throws Exception {
        conversation("О-191026-0001", "it-owner1", owner(200), administrator(100));
        conversation("О-191026-0002", "it-owner1", owner(30));
        conversation("О-191026-0003", "it-owner1", owner(90));

        assertThat(numbers(admin, q("191026"))).containsExactly("О-191026-0003", "О-191026-0002", "О-191026-0001");
    }

    @Test
    void aVeryLongQueryDoesNotBreakAnything() throws Exception {
        numbersForSearch();
        assertThat(numbers(admin, q("1".repeat(500)))).isEmpty();
    }
}
