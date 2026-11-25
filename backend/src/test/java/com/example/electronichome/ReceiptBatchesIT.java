package com.example.electronichome;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.example.electronichome.PeriodTestSupport.MutableClock;
import com.example.electronichome.statement.StatementFixture;
import com.example.electronichome.statement.StatementFixture.Account;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// The upload of a statement of the charges and the receipts made from it, on a real database and a real storage; "today" is 18 October 2026.
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReceiptBatchesIT {

    private static String receiptsBucket;
    private static String statementsBucket;

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        String[] buckets = SeaweedFsTestContainer.register(registry);
        receiptsBucket = buckets[0];
        statementsBucket = buckets[3];
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    S3Client s3;

    @Autowired
    MutableClock clock;

    private final JsonMapper json = JsonMapper.builder().build();
    private String admin;
    private String owner5;
    private String owner6;
    private String resident;

    @BeforeEach
    void prepare() throws Exception {
        clock.set("2026-10-18");
        jdbc.update("DELETE FROM receipt_batches");
        emptyBucket(receiptsBucket);
        emptyBucket(statementsBucket);
        jdbc.update("DELETE FROM receipts");
        for (String login : List.of("it-owner5", "it-owner6", "it-resident")) {
            AuthSupport.deleteUser(jdbc, login);
        }
        jdbc.update("DELETE FROM accounts WHERE number IN ('000000000005', '000000000006')");
        for (String apartment : List.of("5", "6")) {
            jdbc.update("""
                    INSERT INTO accounts (number, address_id)
                    SELECT lpad(?, 12, '0'), id FROM addresses WHERE street = 'ул. Тестовая' AND house = 'Дом' AND apartment = ?""", apartment, apartment);
        }
        AuthSupport.createUser(jdbc, "it-owner5", "OWNER", true, "000000000005");
        AuthSupport.createUser(jdbc, "it-owner6", "OWNER", true, "000000000006");
        AuthSupport.createUser(jdbc, "it-resident", "RESIDENT", true, "000000000005");
        admin = AuthSupport.adminBearer(jdbc, port);
        owner5 = AuthSupport.bearer(port, "it-owner5");
        owner6 = AuthSupport.bearer(port, "it-owner6");
        resident = AuthSupport.bearer(port, "it-resident");
    }

    private static byte[] statement() {
        return StatementFixture.xls("Сентябрь 2026 г.",
                new Account("1", "Иванов Иван Иванович", "Кв. 1", "100.10", "20.20"),
                new Account("5", "Петров Пётр Петрович", "Кв. 5", "8655.95", "3631.72"),
                new Account("6", "Сидорова Анна Петровна", "Кв. 6", "10.00", "1.50"),
                new Account("117", "ПАО Завод", "Кв. 117", "1.00", "2.00"),
                new Account("003", "Юлиян ООО", "Оф. 3", "3.00", "4.00"),
                new Account("9", "Орлов Олег Олегович", "Кв. 9", "5.00", "6.00").withoutCapitalRepair());
    }

    // ---- helpers

    private record Part(String name, String filename, byte[] content) {
    }

    private HttpResponse<String> upload(String bearer, Part... parts) throws Exception {
        String boundary = "----it" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Part part : parts) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + part.name() + "\"; filename=\"" + part.filename()
                    + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(part.content());
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/receipt-batches"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if (bearer != null) {
            request.header("Authorization", bearer);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> uploadFile(String bearer, byte[] xls) throws Exception {
        return upload(bearer, new Part("file", "ведомость.xls", xls));
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            request.header("Authorization", bearer);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> download(String account, String month, String bearer) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/accounts/" + account + "/receipts/" + month))
                .header("Authorization", bearer).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    // uploads the statement and waits until the batch is done; returns its detail
    private JsonNode uploadAndWait(byte[] xls) throws Exception {
        HttpResponse<String> response = uploadFile(admin, xls);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        long id = json.readTree(response.body()).get("id").asLong();
        for (int i = 0; i < 200; i++) {
            JsonNode batch = json.readTree(get("/api/receipt-batches/" + id, admin).body());
            if (List.of("done", "failed").contains(batch.get("status").asString())) {
                return batch;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("The batch did not finish");
    }

    private void emptyBucket(String bucket) {
        try {
            for (var object : s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build()).contents()) {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(object.key()).build());
            }
        } catch (NoSuchBucketException e) {
            // not made yet
        }
    }

    private int objects(String bucket) {
        try {
            return s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build()).contents().size();
        } catch (NoSuchBucketException e) {
            // the bucket is made at the first use
            return 0;
        }
    }

    // ---- tests

    @Test
    void uploadAnswersAtOnceAndMakesTheReceiptsOfAllTheAccountsInTheBackground() throws Exception {
        HttpResponse<String> response = uploadFile(admin, statement());

        assertThat(response.statusCode()).isEqualTo(202);
        JsonNode accepted = json.readTree(response.body());
        assertThat(accepted.get("month").asString()).isEqualTo("2026-09");
        assertThat(accepted.get("total").asInt()).isEqualTo(6);
        assertThat(List.of("queued", "running", "done")).contains(accepted.get("status").asString());
        assertThat(objects(statementsBucket)).isEqualTo(1);

        JsonNode batch = uploadAndWaitFor(accepted.get("id").asLong());
        assertThat(batch.get("status").asString()).isEqualTo("done");
        assertThat(batch.get("processed").asInt()).isEqualTo(6);
        assertThat(batch.get("created").asInt()).isEqualTo(3);
        assertThat(batch.get("skipped").asInt()).isEqualTo(2);
        assertThat(batch.get("failed").asInt()).isEqualTo(1);
        assertThat(batch.get("finished_at").isNull()).isFalse();
    }

    private JsonNode uploadAndWaitFor(long id) throws Exception {
        for (int i = 0; i < 200; i++) {
            JsonNode batch = json.readTree(get("/api/receipt-batches/" + id, admin).body());
            if (List.of("done", "failed").contains(batch.get("status").asString())) {
                return batch;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("The batch did not finish");
    }

    @Test
    void publishesAPdfWithTheAmountsFromTheStatementForEveryAccountThatExists() throws Exception {
        uploadAndWait(statement());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM receipts", Integer.class)).isEqualTo(3);
        var row = jdbc.queryForMap("""
                SELECT r.utilities_amount, r.capital_repair_amount, r.amounts_source, r.period_month::text AS month
                FROM receipts r JOIN accounts a ON a.id = r.account_id WHERE a.number = '000000000005'""");
        assertThat(row.get("utilities_amount").toString()).isEqualTo("8655.95");
        assertThat(row.get("capital_repair_amount").toString()).isEqualTo("3631.72");
        assertThat(row.get("amounts_source")).isEqualTo("PARSED");
        assertThat(row.get("month")).isEqualTo("2026-09-01");
        assertThat(objects(receiptsBucket)).isEqualTo(3);
    }

    @Test
    void theOwnerDownloadsThePdfOfHisAccountAndOthersAreRefused() throws Exception {
        uploadAndWait(statement());

        HttpResponse<byte[]> own = download("000000000005", "2026-09", owner5);
        assertThat(own.statusCode()).isEqualTo(200);
        assertThat(new String(own.body(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(download("000000000005", "2026-09", owner6).statusCode()).isEqualTo(403);
        assertThat(download("000000000005", "2026-09", resident).statusCode()).isEqualTo(403);
        JsonNode list = json.readTree(get("/api/accounts/000000000005/receipts", owner5).body());
        assertThat(list.get("receipts").get(0).get("month").asString()).isEqualTo("2026-09");
    }

    @Test
    void skipsWhatIsNotAnAccountOfTheDatabaseAndReportsWhy() throws Exception {
        JsonNode batch = uploadAndWait(statement());

        var items = batch.get("items");
        assertThat(item(items, "117").get("status").asString()).isEqualTo("skipped");
        assertThat(item(items, "117").get("reason_code").asString()).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(item(items, "117").get("reason").asString()).isEqualTo("Лицевой счёт не найден");
        // "003" is an office, not the account 3: it never gets the receipt of the apartment 3
        assertThat(item(items, "003").get("status").asString()).isEqualTo("skipped");
        assertThat(item(items, "9").get("status").asString()).isEqualTo("failed");
        assertThat(item(items, "9").get("reason_code").asString()).isEqualTo("MISSING_PART");
        assertThat(item(items, "5").get("status").asString()).isEqualTo("created");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM receipts r JOIN accounts a ON a.id = r.account_id WHERE a.number = '000000000003'", Integer.class)).isZero();
    }

    private static JsonNode item(JsonNode items, String number) {
        for (JsonNode item : items) {
            if (number.equals(item.get("account_number").asString())) {
                return item;
            }
        }
        throw new AssertionError("No item " + number);
    }

    @Test
    void theSecondUploadForTheSameMonthReplacesTheReceipts() throws Exception {
        uploadAndWait(statement());
        String firstKey = jdbc.queryForObject("SELECT file_key FROM receipts r JOIN accounts a ON a.id = r.account_id WHERE a.number = '000000000005'", String.class);

        JsonNode second = uploadAndWait(StatementFixture.xls("Сентябрь 2026 г.",
                new Account("5", "Петров Пётр Петрович", "Кв. 5", "1.00", "2.00")));

        assertThat(second.get("replaced").asInt()).isEqualTo(1);
        assertThat(second.get("created").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM receipts", Integer.class)).isEqualTo(3);
        var row = jdbc.queryForMap("SELECT file_key, utilities_amount FROM receipts r JOIN accounts a ON a.id = r.account_id WHERE a.number = '000000000005'");
        assertThat(row.get("file_key")).isNotEqualTo(firstKey);
        assertThat(row.get("utilities_amount").toString()).isEqualTo("1.00");
        // the replaced file is gone from the storage
        assertThat(objects(receiptsBucket)).isEqualTo(3);
    }

    @Test
    void onlyAnAdministratorUploadsAndReads() throws Exception {
        assertThat(uploadFile(null, statement()).statusCode()).isEqualTo(401);
        assertThat(uploadFile(owner5, statement()).statusCode()).isEqualTo(403);
        assertThat(uploadFile(resident, statement()).statusCode()).isEqualTo(403);
        assertThat(get("/api/receipt-batches", owner5).statusCode()).isEqualTo(403);
        assertThat(get("/api/receipt-batches/1", resident).statusCode()).isEqualTo(403);
        assertThat(get("/api/receipt-batches", null).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM receipt_batches", Integer.class)).isZero();
        assertThat(objects(statementsBucket)).isZero();
    }

    @Test
    void refusesFilesThatAreNotStatements() throws Exception {
        HttpResponse<String> pdf = uploadFile(admin, "%PDF-1.4 not a sheet".getBytes(StandardCharsets.US_ASCII));
        assertThat(pdf.statusCode()).isEqualTo(415);

        HttpResponse<String> empty = uploadFile(admin, StatementFixture.xls("Сентябрь 2026 г."));
        assertThat(empty.statusCode()).isEqualTo(422);
        assertThat(json.readTree(empty.body()).get("message").asString()).contains("Раздел 1");

        assertThat(uploadFile(admin, new byte[0]).statusCode()).isEqualTo(400);
        assertThat(upload(admin, new Part("other", "x.xls", statement())).statusCode()).isEqualTo(400);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM receipt_batches", Integer.class)).isZero();
        assertThat(objects(statementsBucket)).isZero();
    }

    @Test
    void refusesAMonthThatHasNotCome() throws Exception {
        HttpResponse<String> response = uploadFile(admin, StatementFixture.xls("Ноябрь 2026 г.", new Account("5", "Петров Пётр Петрович", "Кв. 5", "1.00", "2.00")));

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM receipt_batches", Integer.class)).isZero();
    }

    @Test
    void theAnswersCarryNoNamesAddressesOrAmounts() throws Exception {
        JsonNode batch = uploadAndWait(statement());
        String list = get("/api/receipt-batches", admin).body();
        String detail = get("/api/receipt-batches/" + batch.get("id").asLong(), admin).body();

        for (String body : List.of(list, detail)) {
            assertThat(body).doesNotContain("Иванов", "Петров", "Сидорова", "Тестовая", "8655", "source_key", "sha256");
        }
        assertThat(json.readTree(list).get("batches")).hasSize(1);
    }

    @Test
    void theListShowsTheBatchesFromTheNewestToTheOldest() throws Exception {
        uploadAndWait(statement());
        JsonNode second = uploadAndWait(statement());

        JsonNode batches = json.readTree(get("/api/receipt-batches", admin).body()).get("batches");
        assertThat(batches).hasSize(2);
        assertThat(batches.get(0).get("id").asLong()).isEqualTo(second.get("id").asLong());
    }

    @Test
    void anUnknownBatchIsNotFound() throws Exception {
        assertThat(get("/api/receipt-batches/999999", admin).statusCode()).isEqualTo(404);
    }
}
