package com.example.electronichome;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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

// Requests and the chats under them: who sees what, creating, numbers, messages, closing; on a real server and database.
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestsApiIT {

    private static final String ACCOUNT_1 = "000000000001";
    private static final String ACCOUNT_98 = "000000000098";
    private static final String NUMBER = "О-\\d{6}-\\d{4,}";

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
    private String lonely;
    private String resident;
    private String admin;

    @BeforeEach
    void users() throws Exception {
        jdbc.update("INSERT INTO addresses (street, house, apartment, is_test) VALUES ('ул. Тестовая', 'Тест', '98', TRUE)");
        jdbc.update("INSERT INTO accounts (number, address_id, is_test) SELECT ?, id, TRUE FROM addresses WHERE apartment = '98'", ACCOUNT_98);
        AuthSupport.createUser(jdbc, "it-owner1", "OWNER", true, ACCOUNT_1);
        AuthSupport.createUser(jdbc, "it-owner2", "OWNER", true, ACCOUNT_98);
        AuthSupport.createUser(jdbc, "it-lonely", "OWNER", true);
        AuthSupport.createUser(jdbc, "it-resident", "RESIDENT", true, ACCOUNT_1);
        AuthSupport.createUser(jdbc, "it-admin-rq", "ADMIN", true);
        owner1 = AuthSupport.bearer(port, "it-owner1");
        owner2 = AuthSupport.bearer(port, "it-owner2");
        lonely = AuthSupport.bearer(port, "it-lonely");
        resident = AuthSupport.bearer(port, "it-resident");
        admin = AuthSupport.bearer(port, "it-admin-rq");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM requests WHERE NOT is_test");
        jdbc.update("DELETE FROM users WHERE login LIKE 'it-%'");
        jdbc.update("DELETE FROM accounts WHERE number = ?", ACCOUNT_98);
        jdbc.update("DELETE FROM addresses WHERE apartment = '98'");
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

    private JsonNode body(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body());
    }

    private String newRequest(String text) {
        return json.createObjectNode().put("text", text).toString();
    }

    private String text(String text) {
        return json.createObjectNode().put("text", text).toString();
    }

    private long create(String bearer, String text) throws Exception {
        HttpResponse<String> response = call("POST", "/api/requests", bearer, newRequest(text));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return body(response).get("id").asLong();
    }

    private int write(String bearer, long id, String text) throws Exception {
        // the administrator writes only into a request that is in work
        if (bearer == admin) {
            jdbc.update("UPDATE requests SET status = 'IN_PROGRESS' WHERE id = ? AND status = 'ACCEPTED'", id);
        }
        return call("POST", "/api/requests/" + id + "/messages", bearer, text(text)).statusCode();
    }

    // The requests are told apart by the text of the first message, which is the preview in the list.
    private List<String> previews(String bearer, String query) throws Exception {
        HttpResponse<String> response = call("GET", "/api/requests" + query, bearer, null);
        assertThat(response.statusCode()).isEqualTo(200);
        List<String> previews = new ArrayList<>();
        body(response).get("requests").forEach(item -> previews.add(item.get("last_message").get("text").asString()));
        return previews;
    }

    private long sequenceOf(String number) {
        return Long.parseLong(number.substring(number.lastIndexOf('-') + 1));
    }

    // ---- the numbers

    @Test
    void aRequestGetsANumberWithTheDateOfTodayAndNoSubject() throws Exception {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMyy"));

        JsonNode item = body(call("POST", "/api/requests", owner1, newRequest("Течёт кран")));

        assertThat(item.get("number").asString()).matches(NUMBER);
        assertThat(item.get("number").asString()).startsWith("О-" + today + "-");
        assertThat(item.has("subject")).isFalse();
    }

    @Test
    void theSequenceRunsThroughAllRequestsAndNeverBacksUp() throws Exception {
        long first = sequenceOf(body(call("POST", "/api/requests", owner1, newRequest("Первая"))).get("number").asString());
        long second = sequenceOf(body(call("POST", "/api/requests", owner2, newRequest("Вторая"))).get("number").asString());
        // the date of a number is not a reason to start again: even another day goes on
        jdbc.update("UPDATE requests SET created_at = created_at - interval '3 days'");
        long third = sequenceOf(body(call("POST", "/api/requests", owner1, newRequest("Третья"))).get("number").asString());

        assertThat(second).isEqualTo(first + 1);
        assertThat(third).isEqualTo(second + 1);
    }

    @Test
    void theNumbersAreFourDigitsWideAtLeastAndUnique() throws Exception {
        List<String> numbers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            numbers.add(body(call("POST", "/api/requests", owner1, newRequest("Заявка " + i))).get("number").asString());
        }

        assertThat(numbers).doesNotHaveDuplicates();
        assertThat(numbers).allMatch(number -> number.matches("О-\\d{6}-\\d{4,}"));
        assertThat(numbers).isSorted();
    }

    @Test
    void theClientCannotChooseTheNumberOrTheSubject() throws Exception {
        String body = json.createObjectNode().put("text", "Текст").put("number", "О-010101-9999").put("subject", "Моя тема").toString();

        HttpResponse<String> response = call("POST", "/api/requests", owner1, body);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(body(response).get("number").asString()).isNotEqualTo("О-010101-9999").matches(NUMBER);
        assertThat(response.body()).doesNotContain("Моя тема");
    }

    @Test
    void twoRequestsCreatedAtOnceGetDifferentNumbers() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> body(call("POST", "/api/requests", owner1, newRequest("Одна"))).get("number").asString());
            var b = pool.submit(() -> body(call("POST", "/api/requests", owner2, newRequest("Другая"))).get("number").asString());

            long x = sequenceOf(a.get());
            long y = sequenceOf(b.get());
            assertThat(x).isNotEqualTo(y);
            assertThat(Math.abs(x - y)).isEqualTo(1);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void theNumberStaysTheSameWhenTheMessagesGoOn() throws Exception {
        long id = create(owner1, "Текст");
        String number = body(call("GET", "/api/requests/" + id, owner1, null)).get("number").asString();

        write(admin, id, "Ответ");
        write(owner1, id, "Ещё вопрос");

        assertThat(body(call("GET", "/api/requests/" + id, owner1, null)).get("number").asString()).isEqualTo(number);
    }

    // ---- the list

    @Test
    void anOwnerSeesOnlyOwnRequestsTheLongestWaitingFirst() throws Exception {
        create(owner1, "Первая");
        create(owner2, "Чужая");
        create(owner1, "Вторая");

        assertThat(previews(owner1, "")).containsExactly("Первая", "Вторая");
        assertThat(previews(owner2, "")).containsExactly("Чужая");
    }

    @Test
    void anAdministratorSeesAllRequestsWithTheApartmentAndTheAuthor() throws Exception {
        create(owner1, "Первая");
        create(owner2, "Чужая");

        HttpResponse<String> response = call("GET", "/api/requests", admin, null);

        JsonNode items = body(response).get("requests");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(previews(admin, "")).contains("Первая", "Чужая");
        JsonNode foreign = null;
        for (JsonNode item : items) {
            if (item.get("last_message").get("text").asString().equals("Чужая")) {
                foreign = item;
            }
        }
        assertThat(foreign).isNotNull();
        assertThat(foreign.get("last_message").get("text").asString()).isEqualTo("Чужая");
        assertThat(foreign.get("number").asString()).matches(NUMBER);
        assertThat(foreign.get("account").get("apartment").asString()).isEqualTo("98");
        assertThat(foreign.get("account").get("number").asString()).isEqualTo(ACCOUNT_98);
        assertThat(foreign.get("author").get("login").asString()).isEqualTo("it-owner2");
    }

    @Test
    void theListHasNumbersNoStorageFieldsAndAShortLastMessage() throws Exception {
        create(owner1, "я".repeat(300));

        JsonNode item = body(call("GET", "/api/requests", owner1, null)).get("requests").get(0);

        assertThat(item.get("number").asString()).matches(NUMBER);
        assertThat(item.has("subject")).isFalse();
        assertThat(item.has("is_test")).isFalse();
        assertThat(item.has("author_id")).isFalse();
        assertThat(item.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(item.get("kind").asString()).isEqualTo("GENERAL");
        assertThat(item.get("awaiting_reply").asBoolean()).isTrue();
        assertThat(item.get("last_message").get("text").asString()).hasSize(120);
        assertThat(item.get("last_message").get("author_role").asString()).isEqualTo("OWNER");
        assertThat(item.get("created_at").asString()).isNotBlank();
        assertThat(item.get("last_message_at").asString()).isNotBlank();
    }

    @Test
    void theListGivesAPageOfFiveAndTheTotal() throws Exception {
        jdbc.update("""
                INSERT INTO requests (account_id, author_id, number)
                SELECT a.id, u.id, 'О-000000-' || g FROM generate_series(1, 205) g, accounts a, users u
                WHERE a.number = ? AND u.login = 'it-owner1'""", ACCOUNT_1);
        jdbc.update("INSERT INTO request_messages (request_id, author_id, body) SELECT r.id, r.author_id, 'текст' FROM requests r WHERE NOT r.is_test");

        assertThat(previews(owner1, "")).hasSize(5);
        JsonNode page = body(call("GET", "/api/requests?page=41", owner1, null));
        assertThat(page.get("requests")).hasSize(5);
        assertThat(page.get("total").asLong()).isEqualTo(205);
        assertThat(body(call("GET", "/api/requests?page=42", owner1, null)).get("requests")).isEmpty();
    }

    @Test
    void aLastReplyOfTheAdministratorLiftsTheRequestAndShowsItsRole() throws Exception {
        long first = create(owner1, "Первая");
        long second = create(owner1, "Вторая");
        assertThat(previews(owner1, "")).containsExactly("Первая", "Вторая");

        assertThat(write(admin, first, "Ответ")).isEqualTo(201);

        JsonNode items = body(call("GET", "/api/requests", owner1, null)).get("requests");
        assertThat(items.get(0).get("id").asLong()).isEqualTo(second);
        assertThat(items.get(0).get("awaiting_reply").asBoolean()).isTrue();
        assertThat(items.get(1).get("id").asLong()).isEqualTo(first);
        assertThat(items.get(1).get("awaiting_reply").asBoolean()).isFalse();
        assertThat(items.get(1).get("last_message").get("author_role").asString()).isEqualTo("ADMIN");
        assertThat(items.get(1).get("last_message").get("text").asString()).isEqualTo("Ответ");
    }

    // ---- creating

    @Test
    void anOwnerCreatesARequestForTheFirstAccountByDefault() throws Exception {
        HttpResponse<String> response = call("POST", "/api/requests", owner1, newRequest("  Подтекает в ванной  "));

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode item = body(response);
        assertThat(item.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(item.get("kind").asString()).isEqualTo("GENERAL");
        assertThat(item.get("awaiting_reply").asBoolean()).isTrue();
        assertThat(item.get("account").get("number").asString()).isEqualTo(ACCOUNT_1);
        assertThat(item.get("author").get("login").asString()).isEqualTo("it-owner1");
        assertThat(item.get("last_message").get("text").asString()).isEqualTo("Подтекает в ванной");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM request_messages WHERE request_id = ?", Integer.class, item.get("id").asLong())).isEqualTo(1);
    }

    @Test
    void anOwnerMayNameOwnAccountButNotAForeignOne() throws Exception {
        String own = json.createObjectNode().put("text", "текст").put("account_number", ACCOUNT_1).toString();
        String foreign = json.createObjectNode().put("text", "текст").put("account_number", ACCOUNT_98).toString();

        assertThat(call("POST", "/api/requests", owner1, own).statusCode()).isEqualTo(201);
        assertThat(call("POST", "/api/requests", owner1, foreign).statusCode()).isEqualTo(403);
        assertThat(previews(owner1, "")).hasSize(1);
    }

    @Test
    void wrongTextsAreRefusedAndTheSequenceIsNotSpentOnThem() throws Exception {
        long before = sequenceOf(body(call("POST", "/api/requests", owner1, newRequest("Первая"))).get("number").asString());

        assertThat(call("POST", "/api/requests", owner1, newRequest("")).statusCode()).isEqualTo(400);
        assertThat(call("POST", "/api/requests", owner1, newRequest("   ")).statusCode()).isEqualTo(400);
        assertThat(call("POST", "/api/requests", owner1, newRequest("с".repeat(2001))).statusCode()).isEqualTo(400);
        assertThat(call("POST", "/api/requests", owner1, "{}").statusCode()).isEqualTo(400);
        assertThat(call("POST", "/api/requests", owner1, "не json").statusCode()).isEqualTo(400);

        long after = sequenceOf(body(call("POST", "/api/requests", owner1, newRequest("с".repeat(2000)))).get("number").asString());
        assertThat(after).isEqualTo(before + 1);
        assertThat(previews(owner1, "")).hasSize(2);
    }

    @Test
    void anOwnerWithoutAccountsCannotCreateARequest() throws Exception {
        assertThat(call("POST", "/api/requests", lonely, newRequest("текст")).statusCode()).isEqualTo(409);
    }

    @Test
    void onlyOwnersCreateRequests() throws Exception {
        assertThat(call("POST", "/api/requests", admin, newRequest("текст")).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/api/requests", resident, newRequest("текст")).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/api/requests", null, newRequest("текст")).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM requests WHERE NOT is_test", Integer.class)).isZero();
    }

    // ---- the card

    @Test
    void aRequestIsSeenByItsOwnerAndByTheAdministratorOnly() throws Exception {
        long id = create(owner1, "текст");

        assertThat(call("GET", "/api/requests/" + id, owner1, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/requests/" + id, admin, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/requests/" + id, owner2, null).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/api/requests/" + id, resident, null).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/api/requests/" + id, null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void aForeignRequestLooksLikeAMissingOne() throws Exception {
        long id = create(owner1, "Секретный текст");

        HttpResponse<String> foreign = call("GET", "/api/requests/" + id, owner2, null);
        HttpResponse<String> missing = call("GET", "/api/requests/9999999", owner2, null);

        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(foreign.body()).doesNotContain("Секретный");
        assertThat(call("GET", "/api/requests/9999999", admin, null).statusCode()).isEqualTo(404);
    }

    // ---- the messages

    @Test
    void theMessagesComeInOrderWithTheAuthorAndTheMineFlag() throws Exception {
        long id = create(owner1, "Первое");
        write(admin, id, "Второе");
        write(owner1, id, "Третье");

        JsonNode asOwner = body(call("GET", "/api/requests/" + id + "/messages", owner1, null)).get("messages");
        JsonNode asAdmin = body(call("GET", "/api/requests/" + id + "/messages", admin, null)).get("messages");

        assertThat(asOwner).hasSize(3);
        assertThat(asOwner.get(0).get("text").asString()).isEqualTo("Первое");
        assertThat(asOwner.get(1).get("text").asString()).isEqualTo("Второе");
        assertThat(asOwner.get(2).get("text").asString()).isEqualTo("Третье");
        assertThat(asOwner.get(0).get("mine").asBoolean()).isTrue();
        assertThat(asOwner.get(1).get("mine").asBoolean()).isFalse();
        assertThat(asOwner.get(1).get("author").get("role").asString()).isEqualTo("ADMIN");
        assertThat(asOwner.get(0).get("author").get("login").asString()).isEqualTo("it-owner1");
        assertThat(asAdmin.get(1).get("mine").asBoolean()).isTrue();
        assertThat(asAdmin.get(0).get("mine").asBoolean()).isFalse();
        assertThat(asOwner.get(0).has("is_test")).isFalse();
        assertThat(asOwner.get(0).get("id").asLong()).isLessThan(asOwner.get(1).get("id").asLong());
    }

    @Test
    void onlyNewMessagesCanBeAsked() throws Exception {
        long id = create(owner1, "Первое");
        write(admin, id, "Второе");
        write(owner1, id, "Третье");
        JsonNode all = body(call("GET", "/api/requests/" + id + "/messages", owner1, null)).get("messages");
        long second = all.get(1).get("id").asLong();
        long third = all.get(2).get("id").asLong();

        JsonNode news = body(call("GET", "/api/requests/" + id + "/messages?after=" + second, owner1, null)).get("messages");
        JsonNode none = body(call("GET", "/api/requests/" + id + "/messages?after=" + third, owner1, null)).get("messages");

        assertThat(news).hasSize(1);
        assertThat(news.get(0).get("text").asString()).isEqualTo("Третье");
        assertThat(none).isEmpty();
        assertThat(call("GET", "/api/requests/" + id + "/messages?after=abc", owner1, null).statusCode()).isEqualTo(400);
    }

    @Test
    void theAnswerOfASentMessageIsTheMessage() throws Exception {
        long id = create(owner1, "Первое");

        HttpResponse<String> response = call("POST", "/api/requests/" + id + "/messages", owner1, text("  Новое сообщение  "));

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode message = body(response);
        assertThat(message.get("text").asString()).isEqualTo("Новое сообщение");
        assertThat(message.get("mine").asBoolean()).isTrue();
        assertThat(message.get("author").get("role").asString()).isEqualTo("OWNER");
    }

    @Test
    void aForeignRequestCannotBeReadOrWrittenTo() throws Exception {
        long id = create(owner1, "Первое");

        assertThat(call("GET", "/api/requests/" + id + "/messages", owner2, null).statusCode()).isEqualTo(404);
        assertThat(write(owner2, id, "Вмешательство")).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM request_messages WHERE request_id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void aResidentHasNoAccessToTheChats() throws Exception {
        long id = create(owner1, "Первое");

        assertThat(call("GET", "/api/requests", resident, null).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/api/requests/" + id + "/messages", resident, null).statusCode()).isEqualTo(403);
        assertThat(write(resident, id, "Привет")).isEqualTo(403);
        assertThat(call("GET", "/api/requests", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void wrongMessagesAreRefused() throws Exception {
        long id = create(owner1, "Первое");

        assertThat(write(owner1, id, "")).isEqualTo(400);
        assertThat(write(owner1, id, "   ")).isEqualTo(400);
        assertThat(write(owner1, id, "с".repeat(2001))).isEqualTo(400);
        assertThat(call("POST", "/api/requests/" + id + "/messages", owner1, "{}").statusCode()).isEqualTo(400);
        assertThat(write(owner1, id, "с".repeat(2000))).isEqualTo(201);
    }

    // ---- no closing

    @Test
    void anOwnerKeepsWritingAfterTheAnswerAndAnswersAreNotLimited() throws Exception {
        long id = create(owner1, "Первое");

        assertThat(write(admin, id, "Ответ")).isEqualTo(201);
        assertThat(write(owner1, id, "Уточнение")).isEqualTo(201);
        assertThat(write(admin, id, "Ещё ответ")).isEqualTo(201);
        assertThat(body(call("GET", "/api/requests/" + id + "/messages", owner1, null)).get("messages")).hasSize(4);
    }

    @Test
    void aRequestIsNotChangedByPatchAndOnlyAnAdministratorMovesItsStatus() throws Exception {
        long id = create(owner1, "Первое");

        assertThat(call("PATCH", "/api/requests/" + id, admin, "{\"status\":\"CLOSED\"}").statusCode()).isEqualTo(405);
        assertThat(call("PUT", "/api/requests/" + id + "/status", owner1, "{\"status\":\"IN_PROGRESS\"}").statusCode()).isEqualTo(403);
        assertThat(call("PUT", "/api/requests/" + id + "/status", resident, "{\"status\":\"IN_PROGRESS\"}").statusCode()).isEqualTo(403);
        assertThat(body(call("GET", "/api/requests/" + id, owner1, null)).get("status").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void theTestConversationIsSeenByTheTestOwnerWithItsNumber() throws Exception {
        String testOwner = AuthSupport.bearer(port, "owner", "Owner-dev-123");

        JsonNode items = body(call("GET", "/api/requests", testOwner, null)).get("requests");
        long id = items.get(0).get("id").asLong();
        JsonNode messages = body(call("GET", "/api/requests/" + id + "/messages", testOwner, null)).get("messages");

        assertThat(items.get(0).get("number").asString()).matches("О-\\d{6}-0001");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).get("author").get("role").asString()).isEqualTo("ADMIN");
    }
}
