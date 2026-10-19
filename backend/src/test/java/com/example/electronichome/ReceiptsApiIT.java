package com.example.electronichome;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.example.electronichome.PeriodTestSupport.MutableClock;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Publishing, listing and downloading receipts on a real database and a real directory; "today" is 18 October 2026.
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "receipts.max-size=2KB")
class ReceiptsApiIT {

    private static final String ACCOUNT = "000000000001";
    private static final String OTHER_ACCOUNT = "000000000098";
    private static final String TOKEN = "dev-admin-token";
    private static final Path DIRECTORY = createDirectory();

    private static Path createDirectory() {
        try {
            return Files.createTempDirectory("receipts-it");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

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
        registry.add("receipts.storage.path", DIRECTORY::toString);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clean() throws IOException {
        clock.set("2026-10-18");
        jdbc.update("DELETE FROM receipts");
        jdbc.update("DELETE FROM accounts WHERE number = ?", OTHER_ACCOUNT);
        jdbc.update("DELETE FROM addresses WHERE apartment = '98'");
        Long address = jdbc.queryForObject(
                "INSERT INTO addresses (street, house, apartment) VALUES ('ул. Тестовая', 'Тест', '98') RETURNING id", Long.class);
        jdbc.update("INSERT INTO accounts (number, address_id) VALUES (?, ?)", OTHER_ACCOUNT, address);
        try (Stream<Path> files = Files.list(DIRECTORY)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    @AfterEach
    void removeTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_receipt_insert ON receipts");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_receipt_insert()");
    }

    // ---- helpers

    private static byte[] pdf(String text) {
        return ("%PDF-1.4\n% " + text + "\n%%EOF\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> put(String account, String month, byte[] body, String token, String contentType) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/api/accounts/" + account + "/receipts/" + URLEncoder.encode(month, StandardCharsets.UTF_8)))
                .header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null) {
            request.header("X-Admin-Token", token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> publish(String account, String month, byte[] body) throws Exception {
        return put(account, month, body, TOKEN, "application/pdf");
    }

    private HttpResponse<byte[]> download(String account, String month) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/accounts/" + account + "/receipts/" + URLEncoder.encode(month, StandardCharsets.UTF_8)))
                .header("Authorization", AuthSupport.adminBearer(jdbc, port)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> list(String account) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/accounts/" + account + "/receipts"))
                .header("Authorization", AuthSupport.adminBearer(jdbc, port)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private List<String> filesInStorage() throws IOException {
        try (Stream<Path> files = Files.list(DIRECTORY)) {
            return files.map(f -> f.getFileName().toString()).sorted().toList();
        }
    }

    private int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM receipts", Integer.class);
    }

    private void assertNothingStored() throws IOException {
        assertThat(rows()).isZero();
        assertThat(filesInStorage()).isEmpty();
    }

    // ---- publishing

    @Test
    void publishesANewReceiptAndDownloadsItByteForByte() throws Exception {
        byte[] content = pdf("october");

        HttpResponse<String> response = publish(ACCOUNT, "2026-10", content);

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("month").asString()).isEqualTo("2026-10");
        assertThat(body.get("size").asLong()).isEqualTo(content.length);
        assertThat(body.get("sha256").asString()).isEqualTo(sha256(content));
        assertThat(body.get("published_at").asString()).isNotBlank();
        assertThat(response.body()).doesNotContain("file_key").doesNotContain("\"id\"");

        HttpResponse<byte[]> file = download(ACCOUNT, "2026-10");
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.body()).isEqualTo(content);
        assertThat(file.headers().firstValue("Content-Type")).contains("application/pdf");
        assertThat(file.headers().firstValue("Content-Length")).contains(String.valueOf(content.length));
        assertThat(file.headers().firstValue("Content-Disposition").orElseThrow()).contains("receipt-000000000001-2026-10.pdf");
    }

    @Test
    void keepsTheMetadataInTheDatabaseAndTheFileInTheStorage() throws Exception {
        byte[] content = pdf("meta");
        publish(ACCOUNT, "2026-10", content);

        var row = jdbc.queryForMap("SELECT period_month::text AS month, file_key, size_bytes, sha256, is_test FROM receipts");

        assertThat(row.get("month")).isEqualTo("2026-10-01");
        assertThat(row.get("size_bytes")).isEqualTo((long) content.length);
        assertThat(row.get("sha256")).isEqualTo(sha256(content));
        assertThat(filesInStorage()).containsExactly((String) row.get("file_key"));
        assertThat(Files.readAllBytes(DIRECTORY.resolve((String) row.get("file_key")))).isEqualTo(content);
    }

    @Test
    void replacesTheReceiptOfTheSameMonthAndRemovesTheOldFile() throws Exception {
        assertThat(publish(ACCOUNT, "2026-10", pdf("first")).statusCode()).isEqualTo(201);
        String firstKey = jdbc.queryForObject("SELECT file_key FROM receipts", String.class);
        byte[] second = pdf("second, corrected");

        HttpResponse<String> response = publish(ACCOUNT, "2026-10", second);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(download(ACCOUNT, "2026-10").body()).isEqualTo(second);
        assertThat(rows()).isEqualTo(1);
        String secondKey = jdbc.queryForObject("SELECT file_key FROM receipts", String.class);
        assertThat(secondKey).isNotEqualTo(firstKey);
        assertThat(filesInStorage()).containsExactly(secondKey);
    }

    @Test
    void acceptsABodyWhateverContentTypeItIsSentWith() throws Exception {
        byte[] content = pdf("curl default");

        // what curl --data-binary sends without a Content-Type of its own
        assertThat(put(ACCOUNT, "2026-10", content, TOKEN, "application/x-www-form-urlencoded").statusCode()).isEqualTo(201);

        assertThat(download(ACCOUNT, "2026-10").body()).isEqualTo(content);
    }

    @Test
    void refusesWithoutAValidToken() throws Exception {
        assertThat(put(ACCOUNT, "2026-10", pdf("x"), null, "application/pdf").statusCode()).isEqualTo(403);
        assertThat(put(ACCOUNT, "2026-10", pdf("x"), "wrong", "application/pdf").statusCode()).isEqualTo(403);
        assertThat(put(ACCOUNT, "2026-10", pdf("x"), "", "application/pdf").statusCode()).isEqualTo(403);
        assertThat(put("999999999999", "2026-10", pdf("x"), null, "application/pdf").statusCode()).isEqualTo(403);

        assertNothingStored();
    }

    @Test
    void refusesWhatIsNotAPdf() throws Exception {
        assertThat(publish(ACCOUNT, "2026-10", "<html>no</html>".getBytes(StandardCharsets.UTF_8)).statusCode()).isEqualTo(400);

        assertNothingStored();
    }

    @Test
    void refusesAnEmptyBody() throws Exception {
        assertThat(publish(ACCOUNT, "2026-10", new byte[0]).statusCode()).isEqualTo(400);

        assertNothingStored();
    }

    @Test
    void refusesAFileOverTheLimit() throws Exception {
        byte[] big = pdf("x".repeat(3000));

        assertThat(publish(ACCOUNT, "2026-10", big).statusCode()).isEqualTo(413);

        assertNothingStored();
    }

    @Test
    void acceptsAFileUpToTheLimit() throws Exception {
        byte[] content = pdf("x".repeat(1900));

        assertThat(publish(ACCOUNT, "2026-10", content).statusCode()).isEqualTo(201);
    }

    @Test
    void refusesAWrongMonth() throws Exception {
        for (String month : List.of("2026-13", "2026-1", "2026-00", "октябрь", "2026-10-01", "202610")) {
            assertThat(publish(ACCOUNT, month, pdf("x")).statusCode()).as(month).isEqualTo(400);
        }

        assertNothingStored();
    }

    @Test
    void refusesAMonthInTheFuture() throws Exception {
        assertThat(publish(ACCOUNT, "2026-11", pdf("x")).statusCode()).isEqualTo(400);
        assertThat(publish(ACCOUNT, "2027-01", pdf("x")).statusCode()).isEqualTo(400);
        assertThat(publish(ACCOUNT, "2026-10", pdf("x")).statusCode()).isEqualTo(201);

        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void refusesAnUnknownAccount() throws Exception {
        assertThat(publish("999999999999", "2026-10", pdf("x")).statusCode()).isEqualTo(404);

        assertNothingStored();
    }

    // ---- list and download

    @Test
    void listsTheReceiptsOfTheAccountNewestFirst() throws Exception {
        publish(ACCOUNT, "2026-08", pdf("a"));
        publish(ACCOUNT, "2026-10", pdf("b"));
        publish(ACCOUNT, "2026-09", pdf("c"));

        HttpResponse<String> response = list(ACCOUNT);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode receipts = json.readTree(response.body()).get("receipts");
        List<String> months = new ArrayList<>();
        receipts.forEach(r -> months.add(r.get("month").asString()));
        assertThat(months).containsExactly("2026-10", "2026-09", "2026-08");
        assertThat(receipts.get(0).get("size").asLong()).isEqualTo(pdf("b").length);
        assertThat(receipts.get(0).get("published_at").asString()).isNotBlank();
        assertThat(response.body()).doesNotContain("file_key").doesNotContain("sha256").doesNotContain("\"id\"");
    }

    @Test
    void listsNothingForAnAccountWithoutReceiptsAndKeepsAccountsApart() throws Exception {
        publish(ACCOUNT, "2026-10", pdf("mine"));

        JsonNode others = json.readTree(list(OTHER_ACCOUNT).body()).get("receipts");

        assertThat(list(OTHER_ACCOUNT).statusCode()).isEqualTo(200);
        assertThat(others.isArray()).isTrue();
        assertThat(others).isEmpty();
    }

    @Test
    void doesNotMixTheReceiptsOfDifferentAccountsOfTheSameMonth() throws Exception {
        publish(ACCOUNT, "2026-10", pdf("first account"));
        publish(OTHER_ACCOUNT, "2026-10", pdf("second account"));

        assertThat(download(ACCOUNT, "2026-10").body()).isEqualTo(pdf("first account"));
        assertThat(download(OTHER_ACCOUNT, "2026-10").body()).isEqualTo(pdf("second account"));
        assertThat(rows()).isEqualTo(2);
    }

    @Test
    void listOfAnUnknownAccountIsNotFound() throws Exception {
        assertThat(list("999999999999").statusCode()).isEqualTo(404);
    }

    @Test
    void downloadIsNotFoundWhenThereIsNothingToSend() throws Exception {
        publish(ACCOUNT, "2026-10", pdf("x"));

        assertThat(download(ACCOUNT, "2026-09").statusCode()).isEqualTo(404);
        assertThat(download(OTHER_ACCOUNT, "2026-10").statusCode()).isEqualTo(404);
        assertThat(download("999999999999", "2026-10").statusCode()).isEqualTo(404);
        assertThat(download(ACCOUNT, "2026-13").statusCode()).isEqualTo(404);
        assertThat(download(ACCOUNT, "октябрь").statusCode()).isEqualTo(404);
    }

    @Test
    void downloadIsNotFoundWhenTheFileIsLost() throws Exception {
        publish(ACCOUNT, "2026-10", pdf("x"));
        Files.delete(DIRECTORY.resolve(jdbc.queryForObject("SELECT file_key FROM receipts", String.class)));

        assertThat(download(ACCOUNT, "2026-10").statusCode()).isEqualTo(404);
        assertThat(rows()).isEqualTo(1);
    }

    // ---- integrity

    @Test
    void leavesNoFileWhenTheDatabaseFails() throws Exception {
        jdbc.execute("""
                CREATE FUNCTION fail_receipt_insert() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'test failure' USING ERRCODE = '23514'; END;
                $$ LANGUAGE plpgsql""");
        jdbc.execute("CREATE TRIGGER fail_receipt_insert BEFORE INSERT ON receipts FOR EACH ROW EXECUTE FUNCTION fail_receipt_insert()");

        HttpResponse<String> response = publish(ACCOUNT, "2026-10", pdf("x"));

        assertThat(response.statusCode()).isEqualTo(409);
        assertNothingStored();
    }

    @Test
    void keepsTheOldFileWhenReplacingFailsInTheDatabase() throws Exception {
        publish(ACCOUNT, "2026-10", pdf("old"));
        String oldKey = jdbc.queryForObject("SELECT file_key FROM receipts", String.class);
        jdbc.execute("""
                CREATE FUNCTION fail_receipt_insert() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'test failure' USING ERRCODE = '23514'; END;
                $$ LANGUAGE plpgsql""");
        jdbc.execute("CREATE TRIGGER fail_receipt_insert BEFORE UPDATE ON receipts FOR EACH ROW EXECUTE FUNCTION fail_receipt_insert()");

        assertThat(publish(ACCOUNT, "2026-10", pdf("new")).statusCode()).isEqualTo(409);

        assertThat(filesInStorage()).containsExactly(oldKey);
        assertThat(download(ACCOUNT, "2026-10").body()).isEqualTo(pdf("old"));
    }

    @Test
    void theDatabaseAllowsOneReceiptPerAccountAndMonth() {
        Long account = jdbc.queryForObject("SELECT id FROM accounts WHERE number = ?", Long.class, ACCOUNT);
        String insert = "INSERT INTO receipts (account_id, period_month, file_key, size_bytes, sha256) VALUES (?, DATE '2026-10-01', ?, 1, 'x')";
        jdbc.update(insert, account, "00000000-0000-0000-0000-000000000001");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(insert, account, "00000000-0000-0000-0000-000000000002"))
                .isInstanceOf(DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.update("INSERT INTO receipts (account_id, period_month, file_key, size_bytes, sha256) VALUES (?, DATE '2026-09-01', ?, 0, 'x')",
                        account, "00000000-0000-0000-0000-000000000003"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void inheritsTheTestFlagOfTheAccount() throws Exception {
        publish(ACCOUNT, "2026-10", pdf("test account"));
        publish(OTHER_ACCOUNT, "2026-10", pdf("real account"));

        assertThat(jdbc.queryForObject(
                "SELECT r.is_test FROM receipts r JOIN accounts a ON a.id = r.account_id WHERE a.number = ?", Boolean.class, ACCOUNT)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT r.is_test FROM receipts r JOIN accounts a ON a.id = r.account_id WHERE a.number = ?", Boolean.class, OTHER_ACCOUNT)).isFalse();
    }

    @Test
    void concurrentPublicationsOfTheSameMonthLeaveOneRecordAndOneFile() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            byte[] content = pdf("version " + i);
            results.add(pool.submit(() -> {
                start.await();
                return publish(ACCOUNT, "2026-10", content).statusCode();
            }));
        }
        start.countDown();
        for (Future<Integer> result : results) {
            assertThat(result.get()).isIn(200, 201, 409);
        }
        pool.shutdown();

        assertThat(rows()).isEqualTo(1);
        assertThat(filesInStorage()).containsExactly(jdbc.queryForObject("SELECT file_key FROM receipts", String.class));
    }

    @Test
    void concurrentReplacementsOfAPublishedReceiptNeverLeaveAnOrphanFile() throws Exception {
        assertThat(publish(ACCOUNT, "2026-10", pdf("first")).statusCode()).isEqualTo(201);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            byte[] content = pdf("replacement " + i);
            results.add(pool.submit(() -> {
                start.await();
                return publish(ACCOUNT, "2026-10", content).statusCode();
            }));
        }
        start.countDown();
        for (Future<Integer> result : results) {
            assertThat(result.get()).isEqualTo(200);
        }
        pool.shutdown();

        assertThat(rows()).isEqualTo(1);
        assertThat(filesInStorage()).containsExactly(jdbc.queryForObject("SELECT file_key FROM receipts", String.class));
    }

    // ---- amounts

    private HttpResponse<String> publishWithAmounts(String account, String month, String query, byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/accounts/" + account + "/receipts/" + month + "?" + query))
                .header("Content-Type", "application/pdf")
                .header("X-Admin-Token", TOKEN)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private java.util.Map<String, Object> amountsRow() {
        return jdbc.queryForMap("SELECT capital_repair_amount, utilities_amount, amounts_source, amounts_at FROM receipts");
    }

    @Test
    void publishesWithTheAmountsAndRecordsWhereTheyComeFrom() throws Exception {
        HttpResponse<String> response = publishWithAmounts(ACCOUNT, "2026-10", "capital_repair=1842.50&utilities=6318.27", pdf("a"));

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("capital_repair_amount").decimalValue()).isEqualByComparingTo("1842.50");
        assertThat(body.get("utilities_amount").decimalValue()).isEqualByComparingTo("6318.27");
        var row = amountsRow();
        assertThat((java.math.BigDecimal) row.get("capital_repair_amount")).isEqualByComparingTo("1842.50");
        assertThat((java.math.BigDecimal) row.get("utilities_amount")).isEqualByComparingTo("6318.27");
        assertThat(row.get("amounts_source")).isEqualTo("MANUAL");
        assertThat(row.get("amounts_at")).isNotNull();
        assertThat(response.body()).doesNotContain("amounts_source").doesNotContain("amounts_at");
    }

    @Test
    void amountsWithOneDigitAfterTheDotAreWrittenWithTwo() throws Exception {
        HttpResponse<String> response = publishWithAmounts(ACCOUNT, "2026-10", "capital_repair=5.5&utilities=7", pdf("a"));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"capital_repair_amount\":5.50").contains("\"utilities_amount\":7.00");
    }

    @Test
    void publishesWithoutAmountsAndHasNoSourceThen() throws Exception {
        HttpResponse<String> response = publish(ACCOUNT, "2026-10", pdf("a"));

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("capital_repair_amount").isNull()).isTrue();
        assertThat(body.get("utilities_amount").isNull()).isTrue();
        var row = amountsRow();
        assertThat(row.values()).containsOnlyNulls();
    }

    @Test
    void replacingTheFileWithoutAmountsDropsTheOldAmounts() throws Exception {
        publishWithAmounts(ACCOUNT, "2026-10", "capital_repair=1&utilities=2", pdf("old"));

        HttpResponse<String> response = publish(ACCOUNT, "2026-10", pdf("new"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("capital_repair_amount").isNull()).isTrue();
        assertThat(amountsRow().values()).containsOnlyNulls();
    }

    @Test
    void replacingTheFileWithNewAmountsStoresTheNewOnesAndTheirTime() throws Exception {
        publishWithAmounts(ACCOUNT, "2026-10", "capital_repair=1&utilities=2", pdf("old"));
        Object firstTime = amountsRow().get("amounts_at");

        HttpResponse<String> response = publishWithAmounts(ACCOUNT, "2026-10", "capital_repair=10.10&utilities=20.20", pdf("new"));

        assertThat(response.statusCode()).isEqualTo(200);
        var row = amountsRow();
        assertThat((java.math.BigDecimal) row.get("capital_repair_amount")).isEqualByComparingTo("10.10");
        assertThat((java.math.BigDecimal) row.get("utilities_amount")).isEqualByComparingTo("20.20");
        assertThat(((java.sql.Timestamp) row.get("amounts_at")).toInstant())
                .isAfterOrEqualTo(((java.sql.Timestamp) firstTime).toInstant());
    }

    @Test
    void zeroIsAnAmountNotAMissingOne() throws Exception {
        HttpResponse<String> response = publishWithAmounts(ACCOUNT, "2026-10", "capital_repair=0&utilities=0.00", pdf("a"));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"capital_repair_amount\":0.00").contains("\"utilities_amount\":0.00");
        assertThat((java.math.BigDecimal) amountsRow().get("capital_repair_amount")).isEqualByComparingTo("0");
        assertThat(amountsRow().get("amounts_source")).isEqualTo("MANUAL");
    }

    @Test
    void acceptsTheBiggestAmountThatFits() throws Exception {
        assertThat(publishWithAmounts(ACCOUNT, "2026-10", "capital_repair=9999999999.99&utilities=0", pdf("a")).statusCode())
                .isEqualTo(201);
    }

    @Test
    void refusesOneAmountWithoutTheOtherAndChangesNothing() throws Exception {
        assertThat(publishWithAmounts(ACCOUNT, "2026-10", "capital_repair=5", pdf("a")).statusCode()).isEqualTo(400);
        assertThat(publishWithAmounts(ACCOUNT, "2026-10", "utilities=5", pdf("a")).statusCode()).isEqualTo(400);
        assertNothingStored();

        publishWithAmounts(ACCOUNT, "2026-10", "capital_repair=1&utilities=2", pdf("kept"));
        assertThat(publishWithAmounts(ACCOUNT, "2026-10", "capital_repair=9", pdf("other")).statusCode()).isEqualTo(400);
        assertThat(download(ACCOUNT, "2026-10").body()).isEqualTo(pdf("kept"));
        assertThat((java.math.BigDecimal) amountsRow().get("capital_repair_amount")).isEqualByComparingTo("1");
        assertThat(filesInStorage()).hasSize(1);
    }

    @Test
    void refusesWhatIsNotAnAmount() throws Exception {
        for (String value : List.of("-1", "12.345", "abc", "1e3", "", "99999999999", "1.", ".5", "+5", "1,5", "NaN")) {
            String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
            assertThat(publishWithAmounts(ACCOUNT, "2026-10", "capital_repair=" + encoded + "&utilities=1", pdf("a")).statusCode())
                    .as("capital_repair=" + value).isEqualTo(400);
            assertThat(publishWithAmounts(ACCOUNT, "2026-10", "capital_repair=1&utilities=" + encoded, pdf("a")).statusCode())
                    .as("utilities=" + value).isEqualTo(400);
        }

        assertNothingStored();
    }

    @Test
    void listsTheAmountsAndNothingAboutTheirSource() throws Exception {
        publishWithAmounts(ACCOUNT, "2026-10", "capital_repair=1842.50&utilities=6318.27", pdf("with"));
        publish(ACCOUNT, "2026-09", pdf("without"));

        HttpResponse<String> response = list(ACCOUNT);

        JsonNode receipts = json.readTree(response.body()).get("receipts");
        assertThat(receipts.get(0).get("month").asString()).isEqualTo("2026-10");
        assertThat(receipts.get(0).get("capital_repair_amount").decimalValue()).isEqualByComparingTo("1842.50");
        assertThat(receipts.get(0).get("utilities_amount").decimalValue()).isEqualByComparingTo("6318.27");
        assertThat(receipts.get(1).get("capital_repair_amount").isNull()).isTrue();
        assertThat(receipts.get(1).get("utilities_amount").isNull()).isTrue();
        assertThat(response.body()).doesNotContain("amounts_source").doesNotContain("amounts_at").doesNotContain("MANUAL");
    }

    @Test
    void theDatabaseKeepsTheAmountsConsistent() {
        Long account = jdbc.queryForObject("SELECT id FROM accounts WHERE number = ?", Long.class, ACCOUNT);
        String base = "INSERT INTO receipts (account_id, period_month, file_key, size_bytes, sha256, capital_repair_amount, "
                + "utilities_amount, amounts_source, amounts_at) VALUES (?, DATE '2026-%s-01', ?, 1, 'x', %s)";
        String key = "00000000-0000-0000-0000-0000000000%02d";

        // consistent: both amounts with a source and a time
        jdbc.update(base.formatted("01", "1, 2, 'MANUAL', now()"), account, key.formatted(1));
        jdbc.update(base.formatted("02", "1, 2, 'PARSED', now()"), account, key.formatted(2));
        // inconsistent
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(base.formatted("03", "1, NULL, 'MANUAL', now()"), account, key.formatted(3)))
                .isInstanceOf(DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(base.formatted("04", "NULL, 2, 'MANUAL', now()"), account, key.formatted(4)))
                .isInstanceOf(DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(base.formatted("05", "1, 2, NULL, now()"), account, key.formatted(5)))
                .isInstanceOf(DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(base.formatted("06", "1, 2, 'MANUAL', NULL"), account, key.formatted(6)))
                .isInstanceOf(DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(base.formatted("07", "-1, 2, 'MANUAL', now()"), account, key.formatted(7)))
                .isInstanceOf(DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(base.formatted("08", "1, 2, 'GUESSED', now()"), account, key.formatted(8)))
                .isInstanceOf(DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(base.formatted("09", "NULL, NULL, 'MANUAL', now()"), account, key.formatted(9)))
                .isInstanceOf(DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(base.formatted("10", "NULL, NULL, NULL, now()"), account, key.formatted(10)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(rows()).isEqualTo(2);
    }
}
