package com.example.electronichome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.example.electronichome.account.MeterActCleanup;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Object;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// The change of the verification date of a meter with an act: the upload, the draft and the request, the confirmation
// of the administrator, the archive of the old act, the statuses of the requests and the rights; on a real server.
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MeterVerificationIT {

    private static final String ACCOUNT_1 = "000000000001";
    private static final String ACCOUNT_97 = "000000000097";
    private static final String METER = "Тест 1";
    private static final String METER_PATH = "/api/accounts/" + ACCOUNT_1 + "/meters/" + "%D0%A2%D0%B5%D1%81%D1%82%201";
    private static final int MB = 1024 * 1024;

    private static String attachmentsBucket;
    private static String actsBucket;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        String[] buckets = SeaweedFsTestContainer.register(registry);
        attachmentsBucket = buckets[1];
        actsBucket = buckets[2];
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    S3Client s3;

    @Autowired
    MeterActCleanup cleanup;

    private final JsonMapper json = JsonMapper.builder().build();
    private String owner1;
    private String owner2;
    private String resident;
    private String admin;

    @BeforeEach
    void users() throws Exception {
        jdbc.update("INSERT INTO addresses (street, house, apartment, is_test) VALUES ('ул. Тестовая', 'Тест', '97', TRUE)");
        jdbc.update("INSERT INTO accounts (number, address_id, is_test) SELECT ?, id, TRUE FROM addresses WHERE apartment = '97'", ACCOUNT_97);
        AuthSupport.createUser(jdbc, "it-owner1", "OWNER", true, ACCOUNT_1);
        AuthSupport.createUser(jdbc, "it-owner2", "OWNER", true, ACCOUNT_97);
        AuthSupport.createUser(jdbc, "it-resident", "RESIDENT", true, ACCOUNT_1);
        AuthSupport.createUser(jdbc, "it-admin-mv", "ADMIN", true);
        owner1 = AuthSupport.bearer(port, "it-owner1");
        owner2 = AuthSupport.bearer(port, "it-owner2");
        resident = AuthSupport.bearer(port, "it-resident");
        admin = AuthSupport.bearer(port, "it-admin-mv");
    }

    @AfterEach
    void cleanUp() {
        jdbc.execute("DROP TRIGGER IF EXISTS it_fail_attachment ON request_attachments");
        jdbc.update("DELETE FROM meter_act_uploads");
        jdbc.update("DELETE FROM meter_verification_acts");
        jdbc.update("DELETE FROM requests WHERE NOT is_test");
        jdbc.update("DELETE FROM users WHERE login LIKE 'it-%'");
        jdbc.update("DELETE FROM accounts WHERE number = ?", ACCOUNT_97);
        jdbc.update("DELETE FROM addresses WHERE apartment = '97'");
        jdbc.update("UPDATE water_meters SET verification_date = DATE '2028-01-01' WHERE number = 'Тест 1' AND is_test");
        for (String bucket : List.of(actsBucket, attachmentsBucket)) {
            for (String key : keys(bucket)) {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            }
        }
    }

    // ---- helpers

    private record Part(String name, String filename, byte[] content) {
    }

    private HttpResponse<String> upload(String bearer, String filename, byte[] content) throws Exception {
        return upload(METER_PATH, bearer, new Part("file", filename, content));
    }

    private HttpResponse<String> upload(String path, String bearer, Part... parts) throws Exception {
        String boundary = "----it" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Part part : parts) {
            body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(("Content-Disposition: form-data; name=\"" + part.name() + "\"; filename=\"" + part.filename() + "\"\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            body.write(part.content());
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path + "/verification-act-uploads"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if (bearer != null) {
            request.header("Authorization", bearer);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
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

    private static byte[] pdf() {
        byte[] content = new byte[500];
        Arrays.fill(content, (byte) 'x');
        System.arraycopy("%PDF-1.4".getBytes(StandardCharsets.US_ASCII), 0, content, 0, 8);
        return content;
    }

    private static byte[] png() {
        byte[] content = new byte[300];
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 0, content, 0, 8);
        return content;
    }

    private String uploaded(String bearer) throws Exception {
        HttpResponse<String> response = upload(bearer, "act.pdf", pdf());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return body(response).get("file_key").asString();
    }

    private String change(String date, String key) {
        return json.createObjectNode().put("verification_date", date).put("act_file_key", key).toString();
    }

    private HttpResponse<String> put(String bearer, String date, String key) throws Exception {
        return call("PUT", METER_PATH + "/verification-date", bearer, change(date, key));
    }

    private String dateOfTheMeterInTheApi() throws Exception {
        JsonNode meters = body(call("GET", "/api/accounts/" + ACCOUNT_1, owner1, null)).get("meters");
        for (JsonNode meter : meters) {
            if (METER.equals(meter.get("number").asString())) {
                return meter.get("verification_date").asString();
            }
        }
        throw new AssertionError("no meter");
    }

    private List<String> keys(String bucket) {
        try {
            return s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build()).contents().stream()
                    .map(S3Object::key).sorted().toList();
        } catch (NoSuchBucketException e) {
            return List.of();
        }
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int acts(String condition) {
        return count("SELECT count(*) FROM meter_verification_acts WHERE " + condition);
    }

    private long requestOf(HttpResponse<String> response) throws Exception {
        return body(response).get("request").get("id").asLong();
    }

    private int step(String bearer, long request, String status) throws Exception {
        return call("PUT", "/api/requests/" + request + "/status", bearer, "{\"status\":\"" + status + "\"}").statusCode();
    }

    private String statusOf(long request) {
        return jdbc.queryForObject("SELECT status FROM requests WHERE id = ?", String.class, request);
    }

    // ---- the upload

    @Test
    void anOwnerUploadsAPdfAndTheMeterIsNotChanged() throws Exception {
        HttpResponse<String> response = upload(owner1, "act.pdf", pdf());

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode answer = body(response);
        String key = answer.get("file_key").asString();
        assertThat(key).matches("[0-9a-f-]{36}");
        assertThat(answer.get("file_name").asString()).isEqualTo("act.pdf");
        assertThat(answer.get("size_bytes").asLong()).isEqualTo(500);
        assertThat(keys(actsBucket)).containsExactly(key);
        assertThat(count("SELECT count(*) FROM meter_act_uploads")).isEqualTo(1);
        assertThat(response.body()).doesNotContain(actsBucket).doesNotContain("8333").doesNotContain("http://");
        assertThat(dateOfTheMeterInTheApi()).isEqualTo("2028-01-01");
    }

    @Test
    void imagesAreAccepted() throws Exception {
        assertThat(upload(owner1, "Акт.PNG", png()).statusCode()).isEqualTo(201);
    }

    @Test
    void wrongFilesAreRefusedAndNothingIsKept() throws Exception {
        assertThat(upload(owner1, "act.exe", pdf()).statusCode()).isEqualTo(415);
        assertThat(upload(owner1, "act.docx", pdf()).statusCode()).isEqualTo(415);
        assertThat(upload(owner1, "act.pdf", png()).statusCode()).isEqualTo(415);
        assertThat(upload(owner1, "act.pdf", new byte[0]).statusCode()).isEqualTo(400);
        assertThat(upload(METER_PATH, owner1, new Part("other", "act.pdf", pdf())).statusCode()).isEqualTo(400);
        assertThat(keys(actsBucket)).isEmpty();
        assertThat(count("SELECT count(*) FROM meter_act_uploads")).isZero();
    }

    @Test
    void theSizeIsLimitedTo10MegabytesByTheRealBytes() throws Exception {
        byte[] exact = new byte[10 * MB];
        System.arraycopy(pdf(), 0, exact, 0, 8);
        byte[] tooBig = new byte[10 * MB + 1];
        System.arraycopy(pdf(), 0, tooBig, 0, 8);

        assertThat(upload(owner1, "big.pdf", tooBig).statusCode()).isEqualTo(413);
        assertThat(keys(actsBucket)).isEmpty();
        assertThat(upload(owner1, "exact.pdf", exact).statusCode()).isEqualTo(201);
    }

    @Test
    void anUnknownMeterOrAccountIsNotFound() throws Exception {
        assertThat(upload("/api/accounts/" + ACCOUNT_1 + "/meters/nope", owner1, new Part("file", "a.pdf", pdf())).statusCode()).isEqualTo(404);
        assertThat(upload("/api/accounts/000000000555/meters/x", admin, new Part("file", "a.pdf", pdf())).statusCode()).isEqualTo(404);
        assertThat(keys(actsBucket)).isEmpty();
    }

    // ---- the change by an owner: a draft and a request

    @Test
    void anOwnerLeavesADraftAndARequestAndTheDateStays() throws Exception {
        String key = uploaded(owner1);

        HttpResponse<String> response = put(owner1, "2030-05-20", key);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode answer = body(response);
        assertThat(answer.get("applied").asBoolean()).isFalse();
        assertThat(answer.get("verification_date").asString()).isEqualTo("2028-01-01");
        String number = answer.get("request").get("number").asString();
        assertThat(number).matches("П-\\d{6}-\\d{4,}");
        assertThat(dateOfTheMeterInTheApi()).isEqualTo("2028-01-01");
        assertThat(acts("is_draft AND NOT is_archive")).isEqualTo(1);
        assertThat(acts("NOT is_draft")).isZero();
        assertThat(count("SELECT count(*) FROM meter_act_uploads")).isZero();
        assertThat(jdbc.queryForObject("SELECT verification_date::text FROM meter_verification_acts", String.class)).isEqualTo("2030-05-20");

        long id = requestOf(response);
        JsonNode item = body(call("GET", "/api/requests/" + id, owner1, null));
        assertThat(item.get("kind").asString()).isEqualTo("VERIFICATION");
        assertThat(item.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(item.get("account").get("number").asString()).isEqualTo(ACCOUNT_1);
        JsonNode messages = body(call("GET", "/api/requests/" + id + "/messages", admin, null)).get("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("text").asString()).isEqualTo("Добрый день! Выполнил проверку счетчика №Тест 1");
        assertThat(messages.get(0).get("author").get("login").asString()).isEqualTo("it-owner1");
        assertThat(messages.get(0).get("attachments")).hasSize(1);
        assertThat(messages.get(0).get("attachments").get(0).get("name").asString()).isEqualTo("act.pdf");
    }

    @Test
    void theActIsCopiedIntoTheBucketOfTheAttachmentsAndCanBeDownloaded() throws Exception {
        String key = uploaded(owner1);
        long id = requestOf(put(owner1, "2030-05-20", key));
        JsonNode attachment = body(call("GET", "/api/requests/" + id + "/messages", owner1, null)).get("messages").get(0).get("attachments").get(0);

        assertThat(keys(actsBucket)).containsExactly(key);
        assertThat(keys(attachmentsBucket)).hasSize(1).doesNotContain(key);
        HttpResponse<String> file = call("GET", "/api/requests/" + id + "/attachments/" + attachment.get("id").asLong(), admin, null);
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.body()).startsWith("%PDF-1.4");
        assertThat(call("GET", "/api/requests/" + id + "/attachments/" + attachment.get("id").asLong(), owner2, null).statusCode()).isEqualTo(404);
    }

    @Test
    void theRequestsOfAVerificationShareTheSequenceWithTheGeneralOnes() throws Exception {
        HttpResponse<String> general = call("POST", "/api/requests", owner1, "{\"text\":\"Первое\"}");
        String generalNumber = body(general).get("number").asString();
        String verification = body(put(owner1, "2030-05-20", uploaded(owner1))).get("request").get("number").asString();

        assertThat(generalNumber).startsWith("О-");
        assertThat(Long.parseLong(verification.substring(verification.lastIndexOf('-') + 1)))
                .isEqualTo(Long.parseLong(generalNumber.substring(generalNumber.lastIndexOf('-') + 1)) + 1);
    }

    @Test
    void twoDraftsOfOneMeterAreAllowed() throws Exception {
        put(owner1, "2030-01-01", uploaded(owner1));
        put(owner1, "2031-01-01", uploaded(owner1));

        assertThat(acts("is_draft")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM requests WHERE kind = 'VERIFICATION'")).isEqualTo(2);
    }

    // ---- the confirmation

    @Test
    void takingTheRequestIntoWorkPutsTheDateInForce() throws Exception {
        long id = requestOf(put(owner1, "2030-05-20", uploaded(owner1)));

        assertThat(step(admin, id, "IN_PROGRESS")).isEqualTo(200);

        assertThat(dateOfTheMeterInTheApi()).isEqualTo("2030-05-20");
        assertThat(acts("NOT is_draft AND NOT is_archive")).isEqualTo(1);
        assertThat(acts("is_draft")).isZero();
        assertThat(statusOf(id)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void theActInForceIsArchivedWhenTheNextOneIsConfirmed() throws Exception {
        long first = requestOf(put(owner1, "2030-05-20", uploaded(owner1)));
        step(admin, first, "IN_PROGRESS");
        String secondKey = uploaded(owner1);
        long second = requestOf(put(owner1, "2031-06-21", secondKey));
        assertThat(dateOfTheMeterInTheApi()).isEqualTo("2030-05-20");

        assertThat(step(admin, second, "IN_PROGRESS")).isEqualTo(200);

        assertThat(dateOfTheMeterInTheApi()).isEqualTo("2031-06-21");
        assertThat(acts("is_archive")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT verification_date::text FROM meter_verification_acts WHERE is_archive", String.class))
                .isEqualTo("2030-05-20");
        assertThat(jdbc.queryForObject("SELECT object_key FROM meter_verification_acts WHERE NOT is_draft AND NOT is_archive", String.class))
                .isEqualTo(secondKey);
        assertThat(keys(actsBucket)).hasSize(2);
    }

    @Test
    void severalChangesLeaveOnlyTheLastInForce() throws Exception {
        for (String date : List.of("2030-01-01", "2031-01-01", "2032-01-01")) {
            step(admin, requestOf(put(owner1, date, uploaded(owner1))), "IN_PROGRESS");
        }

        assertThat(acts("is_archive")).isEqualTo(2);
        assertThat(acts("NOT is_draft AND NOT is_archive")).isEqualTo(1);
        assertThat(dateOfTheMeterInTheApi()).isEqualTo("2032-01-01");
    }

    @Test
    void theDatabaseAllowsOnlyOneActInForce() throws Exception {
        step(admin, requestOf(put(owner1, "2030-01-01", uploaded(owner1))), "IN_PROGRESS");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO meter_verification_acts (meter_id, object_key, file_name, content_type, size_bytes, sha256, verification_date, created_by)
                SELECT meter_id, ?, 'x.pdf', 'application/pdf', 1, 'x', DATE '2030-01-01', created_by FROM meter_verification_acts LIMIT 1""",
                UUID.randomUUID().toString())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anAdministratorChangesTheDateAtOnceWithoutARequest() throws Exception {
        HttpResponse<String> response = put(admin, "2033-03-03", uploaded(admin));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).get("applied").asBoolean()).isTrue();
        assertThat(body(response).get("verification_date").asString()).isEqualTo("2033-03-03");
        assertThat(body(response).get("request").isNull()).isTrue();
        assertThat(dateOfTheMeterInTheApi()).isEqualTo("2033-03-03");
        assertThat(acts("NOT is_draft AND NOT is_archive")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM requests WHERE kind = 'VERIFICATION'")).isZero();
    }

    @Test
    void aConfirmationWithoutADraftChangesNothing() throws Exception {
        long id = jdbc.queryForObject("""
                INSERT INTO requests (account_id, author_id, number, kind)
                SELECT a.id, u.id, 'П-000000-9999', 'VERIFICATION' FROM accounts a, users u WHERE a.number = ? AND u.login = 'it-owner1' RETURNING id""",
                Long.class, ACCOUNT_1);
        jdbc.update("INSERT INTO request_messages (request_id, author_id, body) SELECT ?, author_id, 'Проверка' FROM requests WHERE id = ?", id, id);

        assertThat(step(admin, id, "IN_PROGRESS")).isEqualTo(409);

        assertThat(statusOf(id)).isEqualTo("ACCEPTED");
    }

    // ---- what is refused

    @Test
    void theChangeNeedsADateAndAnActThatIsTheirs() throws Exception {
        String key = uploaded(owner1);
        String otherUsers = uploaded(admin);

        assertThat(call("PUT", METER_PATH + "/verification-date", owner1, "{\"verification_date\":\"2030-05-20\"}").statusCode()).isEqualTo(400);
        assertThat(put(owner1, "2030-05-20", "").statusCode()).isEqualTo(400);
        assertThat(put(owner1, "2030-05-20", "not-a-key").statusCode()).isEqualTo(400);
        assertThat(put(owner1, "2030-05-20", UUID.randomUUID().toString()).statusCode()).isEqualTo(400);
        assertThat(put(owner1, "2030-05-20", otherUsers).statusCode()).isEqualTo(400);
        for (String date : new String[] {"2030-02-31", "20.05.2030", "1999-12-31", "2101-01-01", "", "2030-5-20"}) {
            assertThat(put(owner1, date, key).statusCode()).as(date).isEqualTo(400);
        }
        assertThat(call("PUT", METER_PATH + "/verification-date", owner1, "{\"act_file_key\":\"" + key + "\"}").statusCode()).isEqualTo(400);
        assertThat(acts("TRUE")).isZero();
        assertThat(count("SELECT count(*) FROM requests WHERE kind = 'VERIFICATION'")).isZero();
        // the upload survived all of that and can still be used
        assertThat(put(owner1, "2030-05-20", key).statusCode()).isEqualTo(201);
    }

    @Test
    void anUploadServesOneChangeAndAPastDateIsFine() throws Exception {
        String key = uploaded(owner1);

        assertThat(put(owner1, "2020-01-01", key).statusCode()).isEqualTo(201);
        assertThat(put(owner1, "2030-01-01", key).statusCode()).isEqualTo(400);
        assertThat(acts("TRUE")).isEqualTo(1);
    }

    @Test
    void anUploadOfAnotherMeterIsRefused() throws Exception {
        String key = uploaded(owner1);

        assertThat(call("PUT", "/api/accounts/" + ACCOUNT_1 + "/meters/%D0%A2%D0%B5%D1%81%D1%82%202/verification-date", owner1,
                change("2030-05-20", key)).statusCode()).isEqualTo(400);
    }

    @Test
    void anOldUploadIsRefusedAndTheCleaningDeletesItAndKeepsTheFreshAndTheUsedOnes() throws Exception {
        String old = uploaded(owner1);
        String fresh = uploaded(owner1);
        String used = uploaded(owner1);
        put(owner1, "2030-05-20", used);
        jdbc.update("UPDATE meter_act_uploads SET created_at = now() - interval '25 hours' WHERE object_key = ?", old);

        assertThat(put(owner1, "2031-01-01", old).statusCode()).isEqualTo(400);
        cleanup.run();

        assertThat(keys(actsBucket)).containsExactlyInAnyOrder(fresh, used);
        assertThat(count("SELECT count(*) FROM meter_act_uploads")).isEqualTo(1);
        assertThat(put(owner1, "2031-01-01", fresh).statusCode()).isEqualTo(201);
    }

    @Test
    void whenTheRequestCannotBeMadeNothingIsKeptAndTheUploadStays() throws Exception {
        String key = uploaded(owner1);
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION it_fail_attachment() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'the test breaks the database'; END $$ LANGUAGE plpgsql""");
        jdbc.execute("CREATE TRIGGER it_fail_attachment BEFORE INSERT ON request_attachments FOR EACH ROW EXECUTE FUNCTION it_fail_attachment()");

        HttpResponse<String> response = put(owner1, "2030-05-20", key);

        assertThat(response.statusCode()).isGreaterThanOrEqualTo(500);
        assertThat(acts("TRUE")).isZero();
        assertThat(count("SELECT count(*) FROM requests WHERE kind = 'VERIFICATION'")).isZero();
        assertThat(keys(attachmentsBucket)).isEmpty();
        assertThat(count("SELECT count(*) FROM meter_act_uploads")).isEqualTo(1);
        assertThat(dateOfTheMeterInTheApi()).isEqualTo("2028-01-01");
    }

    // ---- the rights

    @Test
    void aResidentAnotherOwnerAndAnAnonymousCallerAreRefused() throws Exception {
        String key = uploaded(owner1);

        assertThat(upload(resident, "a.pdf", pdf()).statusCode()).isEqualTo(403);
        assertThat(put(resident, "2030-05-20", key).statusCode()).isEqualTo(403);
        assertThat(upload(owner2, "a.pdf", pdf()).statusCode()).isEqualTo(403);
        assertThat(put(owner2, "2030-05-20", key).statusCode()).isEqualTo(403);
        assertThat(upload(null, "a.pdf", pdf()).statusCode()).isEqualTo(401);
        assertThat(put(null, "2030-05-20", key).statusCode()).isEqualTo(401);
        assertThat(acts("TRUE")).isZero();
        assertThat(keys(actsBucket)).containsExactly(key);
    }

    // ---- the statuses

    @Test
    void aRequestMovesOneStepAtATimeUntilItIsClosed() throws Exception {
        long id = body(call("POST", "/api/requests", owner1, "{\"text\":\"Течёт кран\"}")).get("id").asLong();

        assertThat(step(admin, id, "DONE")).isEqualTo(409);
        assertThat(step(admin, id, "ACCEPTED")).isEqualTo(409);
        assertThat(step(admin, id, "IN_PROGRESS")).isEqualTo(200);
        assertThat(step(admin, id, "IN_PROGRESS")).isEqualTo(409);
        assertThat(step(admin, id, "CLOSED")).isEqualTo(409);
        assertThat(step(admin, id, "DONE")).isEqualTo(200);
        assertThat(step(admin, id, "IN_PROGRESS")).isEqualTo(409);
        assertThat(step(admin, id, "CLOSED")).isEqualTo(200);
        assertThat(step(admin, id, "CLOSED")).isEqualTo(409);
        assertThat(statusOf(id)).isEqualTo("CLOSED");
    }

    @Test
    void anUnknownStatusIsABadRequestAndAMissingRequestIsNotFound() throws Exception {
        long id = body(call("POST", "/api/requests", owner1, "{\"text\":\"Течёт кран\"}")).get("id").asLong();

        assertThat(step(admin, id, "OPEN")).isEqualTo(400);
        assertThat(call("PUT", "/api/requests/" + id + "/status", admin, "{}").statusCode()).isEqualTo(400);
        assertThat(step(admin, id + 100000, "IN_PROGRESS")).isEqualTo(404);
        assertThat(statusOf(id)).isEqualTo("ACCEPTED");
    }

    @Test
    void aClosedRequestTakesNoMessagesAndDoesNotWaitForAnAnswer() throws Exception {
        long id = body(call("POST", "/api/requests", owner1, "{\"text\":\"Течёт кран\"}")).get("id").asLong();
        step(admin, id, "IN_PROGRESS");
        step(admin, id, "DONE");
        assertThat(call("POST", "/api/requests/" + id + "/messages", owner1, "{\"text\":\"Ещё вопрос\"}").statusCode()).isEqualTo(201);
        step(admin, id, "CLOSED");

        assertThat(call("POST", "/api/requests/" + id + "/messages", owner1, "{\"text\":\"Поздно\"}").statusCode()).isEqualTo(409);
        assertThat(call("POST", "/api/requests/" + id + "/messages", admin, "{\"text\":\"Поздно\"}").statusCode()).isEqualTo(409);
        assertThat(call("GET", "/api/requests/" + id + "/messages", owner1, null).statusCode()).isEqualTo(200);
        assertThat(body(call("GET", "/api/requests/" + id, owner1, null)).get("awaiting_reply").asBoolean()).isFalse();
        assertThat(count("SELECT count(*) FROM request_messages WHERE request_id = ?", id)).isEqualTo(2);
    }

    @Test
    void theStatusIsChangedOnlyByAnAdministrator() throws Exception {
        long id = body(call("POST", "/api/requests", owner1, "{\"text\":\"Течёт кран\"}")).get("id").asLong();

        assertThat(step(owner1, id, "IN_PROGRESS")).isEqualTo(403);
        assertThat(step(resident, id, "IN_PROGRESS")).isEqualTo(403);
        assertThat(step(null, id, "IN_PROGRESS")).isEqualTo(401);
        assertThat(statusOf(id)).isEqualTo("ACCEPTED");
    }

    @Test
    void aClientCannotMakeARequestOfTheVerificationKind() throws Exception {
        HttpResponse<String> response = call("POST", "/api/requests", owner1, "{\"text\":\"Хочу П\",\"kind\":\"VERIFICATION\"}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(body(response).get("kind").asString()).isEqualTo("GENERAL");
        assertThat(body(response).get("number").asString()).startsWith("О-");
    }
}
