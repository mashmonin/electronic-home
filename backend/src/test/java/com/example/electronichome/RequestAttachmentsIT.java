package com.example.electronichome;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Object;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// The files in the chats of the requests: sending, the limits, the types, the names, the rights, downloading and cleaning.
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestAttachmentsIT {

    private static final String ACCOUNT_1 = "000000000001";
    private static final String ACCOUNT_96 = "000000000096";
    private static final int MB = 1024 * 1024;

    private static String attachmentsBucket;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        attachmentsBucket = SeaweedFsTestContainer.register(registry)[1];
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    S3Client s3;

    private final JsonMapper json = JsonMapper.builder().build();
    private String owner1;
    private String owner2;
    private String resident;
    private String admin;

    @BeforeEach
    void users() throws Exception {
        jdbc.update("INSERT INTO addresses (street, house, apartment, is_test) VALUES ('ул. Тестовая', 'Тест', '96', TRUE)");
        jdbc.update("INSERT INTO accounts (number, address_id, is_test) SELECT ?, id, TRUE FROM addresses WHERE apartment = '96'", ACCOUNT_96);
        AuthSupport.createUser(jdbc, "it-owner1", "OWNER", true, ACCOUNT_1);
        AuthSupport.createUser(jdbc, "it-owner2", "OWNER", true, ACCOUNT_96);
        AuthSupport.createUser(jdbc, "it-resident", "RESIDENT", true, ACCOUNT_1);
        AuthSupport.createUser(jdbc, "it-admin-at", "ADMIN", true);
        owner1 = AuthSupport.bearer(port, "it-owner1");
        owner2 = AuthSupport.bearer(port, "it-owner2");
        resident = AuthSupport.bearer(port, "it-resident");
        admin = AuthSupport.bearer(port, "it-admin-at");
    }

    @AfterEach
    void cleanUp() {
        jdbc.execute("DROP TRIGGER IF EXISTS it_fail_attachment ON request_attachments");
        jdbc.update("DELETE FROM requests WHERE NOT is_test");
        jdbc.update("DELETE FROM users WHERE login LIKE 'it-%'");
        jdbc.update("DELETE FROM accounts WHERE number = ?", ACCOUNT_96);
        jdbc.update("DELETE FROM addresses WHERE apartment = '96'");
        for (String key : keys()) {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(attachmentsBucket).key(key).build());
        }
    }

    // ---- helpers

    /** One part of a multipart form: a field (no file name) or a file. */
    private record Part(String name, String filename, String contentType, byte[] content) {
        static Part field(String name, String value) {
            return new Part(name, null, null, value.getBytes(StandardCharsets.UTF_8));
        }

        static Part file(String filename, byte[] content) {
            return new Part("files", filename, "application/octet-stream", content);
        }
    }

    private HttpResponse<String> multipart(String path, String bearer, Part... parts) throws Exception {
        String boundary = "----it" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Part part : parts) {
            body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            String disposition = "Content-Disposition: form-data; name=\"" + part.name() + "\""
                    + (part.filename() == null ? "" : "; filename=\"" + part.filename() + "\"");
            body.write((disposition + "\r\n").getBytes(StandardCharsets.UTF_8));
            if (part.contentType() != null) {
                body.write(("Content-Type: " + part.contentType() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
            body.write(part.content());
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
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

    private HttpResponse<byte[]> download(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (bearer != null) {
            request.header("Authorization", bearer);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private JsonNode body(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body());
    }

    private long newRequest(String bearer, String text) throws Exception {
        HttpResponse<String> response = call("POST", "/api/requests", bearer, json.createObjectNode().put("text", text).toString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return body(response).get("id").asLong();
    }

    private List<String> keys() {
        try {
            return s3.listObjectsV2(ListObjectsV2Request.builder().bucket(attachmentsBucket).build()).contents().stream()
                    .map(S3Object::key).sorted().toList();
        } catch (NoSuchBucketException e) {
            return List.of();
        }
    }

    private int messages(long requestId) {
        return jdbc.queryForObject("SELECT count(*) FROM request_messages WHERE request_id = ?", Integer.class, requestId);
    }

    private int attachmentRows() {
        return jdbc.queryForObject("SELECT count(*) FROM request_attachments", Integer.class);
    }

    private static byte[] filled(byte[] header, int size) {
        byte[] content = new byte[Math.max(size, header.length)];
        System.arraycopy(header, 0, content, 0, header.length);
        Arrays.fill(content, header.length, content.length, (byte) 'x');
        return content;
    }

    private static byte[] pdf(int size) {
        return filled("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII), size);
    }

    private static byte[] pdf() {
        return pdf(200);
    }

    private static byte[] jpeg() {
        return filled(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}, 300);
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private String messagesPath(long id) {
        return "/api/requests/" + id + "/messages";
    }

    // ---- sending

    @Test
    void aMessageWithTextAndAFileIsSavedWithTheFileInTheStorage() throws Exception {
        long id = newRequest(owner1, "Первое");
        byte[] photo = jpeg();

        HttpResponse<String> response = multipart(messagesPath(id), owner1, Part.field("text", "Вот фото"), Part.file("протечка.jpg", photo));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode message = body(response);
        assertThat(message.get("text").asString()).isEqualTo("Вот фото");
        assertThat(message.get("attachments")).hasSize(1);
        JsonNode attachment = message.get("attachments").get(0);
        assertThat(attachment.get("name").asString()).isEqualTo("протечка.jpg");
        assertThat(attachment.get("size").asLong()).isEqualTo(photo.length);
        assertThat(attachment.get("content_type").asString()).isEqualTo("image/jpeg");
        assertThat(attachment.size()).isEqualTo(4);

        var row = jdbc.queryForMap("SELECT object_key, file_name, content_type, size_bytes, sha256 FROM request_attachments");
        assertThat(row.get("sha256")).isEqualTo(sha256(photo));
        assertThat(keys()).containsExactly((String) row.get("object_key"));
        assertThat(s3.getObjectAsBytes(GetObjectRequest.builder().bucket(attachmentsBucket).key((String) row.get("object_key")).build()).asByteArray())
                .isEqualTo(photo);
    }

    @Test
    void theAnswerHasNoKeysHashesOrAddressesOfTheStorage() throws Exception {
        long id = newRequest(owner1, "Первое");

        HttpResponse<String> response = multipart(messagesPath(id), owner1, Part.field("text", "x"), Part.file("a.pdf", pdf()));

        String key = jdbc.queryForObject("SELECT object_key FROM request_attachments", String.class);
        assertThat(response.body()).doesNotContain(key).doesNotContain("sha256").doesNotContain("object_key").doesNotContain("is_test")
                .doesNotContain("8333").doesNotContain("http://").doesNotContain(attachmentsBucket);
        assertThat(call("GET", messagesPath(id), owner1, null).body()).doesNotContain(key).doesNotContain(attachmentsBucket);
    }

    @Test
    void aMessageMayBeOnlyAFile() throws Exception {
        long id = newRequest(owner1, "Первое");

        HttpResponse<String> response = multipart(messagesPath(id), owner1, Part.file("схема.pdf", pdf()));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(body(response).get("text").asString()).isEmpty();
        assertThat(body(response).get("attachments")).hasSize(1);
    }

    @Test
    void theListShowsTheNameOfAFileOrTheCountWhenThereIsNoText() throws Exception {
        long one = newRequest(owner1, "Один файл");
        long three = newRequest(owner1, "Три файла");
        long withText = newRequest(owner1, "С текстом");
        multipart(messagesPath(one), owner1, Part.file("схема.pdf", pdf()));
        multipart(messagesPath(three), owner1, Part.file("a.pdf", pdf()), Part.file("b.pdf", pdf()), Part.file("c.pdf", pdf()));
        multipart(messagesPath(withText), owner1, Part.field("text", "Смотрите приложенное"), Part.file("d.pdf", pdf()));

        JsonNode items = body(call("GET", "/api/requests", owner1, null)).get("requests");

        List<String> previews = new ArrayList<>();
        items.forEach(item -> previews.add(item.get("last_message").get("text").asString()));
        assertThat(previews).containsExactlyInAnyOrder("Вложение: схема.pdf", "Вложений: 3", "Смотрите приложенное");
    }

    @Test
    void aMessageWithNeitherTextNorFileIsRefused() throws Exception {
        long id = newRequest(owner1, "Первое");

        assertThat(multipart(messagesPath(id), owner1, Part.field("text", "   ")).statusCode()).isEqualTo(400);
        assertThat(multipart(messagesPath(id), owner1, Part.field("text", "")).statusCode()).isEqualTo(400);
        // a form sent without a chosen file carries an empty part with no name
        assertThat(multipart(messagesPath(id), owner1, Part.field("text", ""), new Part("files", "", "application/octet-stream", new byte[0])).statusCode())
                .isEqualTo(400);
        assertThat(messages(id)).isEqualTo(1);
    }

    @Test
    void anEmptyPartOfAFormDoesNotSpoilAMessageWithText() throws Exception {
        long id = newRequest(owner1, "Первое");

        HttpResponse<String> response = multipart(messagesPath(id), owner1, Part.field("text", "Только текст"),
                new Part("files", "", "application/octet-stream", new byte[0]));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(body(response).get("attachments")).isEmpty();
    }

    @Test
    void theTextIsStillCheckedWhenThereAreFiles() throws Exception {
        long id = newRequest(owner1, "Первое");

        HttpResponse<String> response = multipart(messagesPath(id), owner1, Part.field("text", "с".repeat(2001)), Part.file("a.pdf", pdf()));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(keys()).isEmpty();
        assertThat(attachmentRows()).isZero();
    }

    @Test
    void theFirstMessageOfANewRequestMayCarryAFile() throws Exception {
        HttpResponse<String> response = multipart("/api/requests", owner1, Part.field("text", "Течёт труба"), Part.file("труба.jpg", jpeg()));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        long id = body(response).get("id").asLong();
        assertThat(body(response).get("number").asString()).matches("О-\\d{6}-\\d{4,}");
        JsonNode first = body(call("GET", messagesPath(id), owner1, null)).get("messages").get(0);
        assertThat(first.get("text").asString()).isEqualTo("Течёт труба");
        assertThat(first.get("attachments")).hasSize(1);
        assertThat(keys()).hasSize(1);
    }

    @Test
    void aNewRequestMayBeOnlyAFileAndUsesTheAccountThatIsNamed() throws Exception {
        HttpResponse<String> response = multipart("/api/requests", owner1, Part.field("account_number", ACCOUNT_1), Part.file("акт.pdf", pdf()));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(body(response).get("account").get("number").asString()).isEqualTo(ACCOUNT_1);
        assertThat(body(response).get("last_message").get("text").asString()).isEqualTo("Вложение: акт.pdf");
        assertThat(multipart("/api/requests", owner1, Part.field("account_number", ACCOUNT_96), Part.file("акт.pdf", pdf())).statusCode()).isEqualTo(403);
        assertThat(keys()).hasSize(1);
    }

    @Test
    void aNewRequestWithAFileIsForOwnersOnly() throws Exception {
        assertThat(multipart("/api/requests", admin, Part.file("a.pdf", pdf())).statusCode()).isEqualTo(403);
        assertThat(multipart("/api/requests", resident, Part.file("a.pdf", pdf())).statusCode()).isEqualTo(403);
        assertThat(multipart("/api/requests", null, Part.file("a.pdf", pdf())).statusCode()).isEqualTo(401);
        assertThat(keys()).isEmpty();
    }

    @Test
    void jsonWorksAsBeforeAndHasNoAttachments() throws Exception {
        long id = newRequest(owner1, "Первое");

        HttpResponse<String> response = call("POST", messagesPath(id), owner1, json.createObjectNode().put("text", "Привет").toString());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(body(response).get("attachments")).isEmpty();
        JsonNode all = body(call("GET", messagesPath(id), owner1, null)).get("messages");
        all.forEach(message -> assertThat(message.get("attachments")).isEmpty());
    }

    @Test
    void anAdministratorMayAnswerWithAFile() throws Exception {
        long id = newRequest(owner1, "Первое");

        HttpResponse<String> response = multipart(messagesPath(id), admin, Part.field("text", "Образец заявления"), Part.file("образец.docx", filled(new byte[] {'P', 'K', 3, 4}, 500)));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(body(response).get("author").get("role").asString()).isEqualTo("ADMIN");
        assertThat(body(response).get("attachments").get(0).get("content_type").asString())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @Test
    void theFilesComeWithTheMessagesInOrderAndWithTheNewOnesOnly() throws Exception {
        long id = newRequest(owner1, "Первое");
        multipart(messagesPath(id), owner1, Part.field("text", "один"), Part.file("a.pdf", pdf()), Part.file("b.pdf", pdf()));
        JsonNode first = body(call("GET", messagesPath(id), owner1, null)).get("messages").get(1);
        multipart(messagesPath(id), admin, Part.file("c.pdf", pdf()));

        JsonNode news = body(call("GET", messagesPath(id) + "?after=" + first.get("id").asLong(), owner1, null)).get("messages");

        assertThat(first.get("attachments")).hasSize(2);
        assertThat(first.get("attachments").get(0).get("name").asString()).isEqualTo("a.pdf");
        assertThat(first.get("attachments").get(1).get("name").asString()).isEqualTo("b.pdf");
        assertThat(news).hasSize(1);
        assertThat(news.get(0).get("attachments").get(0).get("name").asString()).isEqualTo("c.pdf");
    }

    // ---- the limits

    @Test
    void sixFilesAreRefusedAndNothingIsSaved() throws Exception {
        long id = newRequest(owner1, "Первое");
        Part[] parts = new Part[6];
        for (int i = 0; i < 6; i++) {
            parts[i] = Part.file("f" + i + ".pdf", pdf());
        }

        HttpResponse<String> response = multipart(messagesPath(id), owner1, parts);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(keys()).isEmpty();
        assertThat(attachmentRows()).isZero();
        assertThat(messages(id)).isEqualTo(1);
    }

    @Test
    void fiveFilesAreAccepted() throws Exception {
        long id = newRequest(owner1, "Первое");
        Part[] parts = new Part[5];
        for (int i = 0; i < 5; i++) {
            parts[i] = Part.file("f" + i + ".pdf", pdf());
        }

        assertThat(multipart(messagesPath(id), owner1, parts).statusCode()).isEqualTo(201);
        assertThat(keys()).hasSize(5);
    }

    @Test
    void aFileOverTenMegabytesIsRefusedAndTheOtherFilesAreNotSaved() throws Exception {
        long id = newRequest(owner1, "Первое");

        HttpResponse<String> response = multipart(messagesPath(id), owner1, Part.file("good.pdf", pdf()), Part.file("big.pdf", pdf(10 * MB + 1)));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(keys()).isEmpty();
        assertThat(attachmentRows()).isZero();
        assertThat(messages(id)).isEqualTo(1);
    }

    @Test
    void aFileOfExactlyTenMegabytesIsAccepted() throws Exception {
        long id = newRequest(owner1, "Первое");

        HttpResponse<String> response = multipart(messagesPath(id), owner1, Part.file("big.pdf", pdf(10 * MB)));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(body(response).get("attachments").get(0).get("size").asLong()).isEqualTo(10L * MB);
    }

    @Test
    void filesOverTwentyFiveMegabytesTogetherAreRefused() throws Exception {
        long id = newRequest(owner1, "Первое");

        HttpResponse<String> response = multipart(messagesPath(id), owner1,
                Part.file("a.pdf", pdf(9 * MB)), Part.file("b.pdf", pdf(9 * MB)), Part.file("c.pdf", pdf(8 * MB)));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(keys()).isEmpty();
        assertThat(messages(id)).isEqualTo(1);
    }

    @Test
    void filesUnderTwentyFiveMegabytesTogetherAreAccepted() throws Exception {
        long id = newRequest(owner1, "Первое");

        HttpResponse<String> response = multipart(messagesPath(id), owner1,
                Part.file("a.pdf", pdf(8 * MB)), Part.file("b.pdf", pdf(8 * MB)), Part.file("c.pdf", pdf(8 * MB)));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(keys()).hasSize(3);
    }

    @Test
    void anEmptyFileIsRefused() throws Exception {
        long id = newRequest(owner1, "Первое");

        HttpResponse<String> response = multipart(messagesPath(id), owner1, Part.file("empty.pdf", new byte[0]));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(keys()).isEmpty();
    }

    // ---- the types

    @Test
    void everyAllowedTypeIsAccepted() throws Exception {
        long id = newRequest(owner1, "Первое");
        byte[] zip = filled(new byte[] {'P', 'K', 3, 4}, 100);
        byte[] compound = filled(new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1}, 100);
        byte[] png = filled(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 100);
        byte[] gif = filled("GIF89a".getBytes(StandardCharsets.US_ASCII), 100);
        byte[] webp = filled("RIFF\u0010\u0000\u0000\u0000WEBP".getBytes(StandardCharsets.ISO_8859_1), 100);
        byte[] text = "Просто текст".getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> response = multipart(messagesPath(id), owner1,
                Part.file("a.JPG", jpeg()), Part.file("b.png", png), Part.file("c.gif", gif), Part.file("d.webp", webp), Part.file("e.pdf", pdf()));
        HttpResponse<String> office = multipart(messagesPath(id), owner1,
                Part.file("f.doc", compound), Part.file("g.docx", zip), Part.file("h.xls", compound), Part.file("i.xlsx", zip), Part.file("j.txt", text));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(office.statusCode()).as(office.body()).isEqualTo(201);
        assertThat(keys()).hasSize(10);
    }

    @Test
    void forbiddenExtensionsAreRefusedWithNothingSaved() throws Exception {
        long id = newRequest(owner1, "Первое");
        for (String name : new String[] {"page.html", "icon.svg", "run.exe", "script.js", "pack.zip", "noextension", "dot.", "SCRIPT.PHP"}) {
            HttpResponse<String> response = multipart(messagesPath(id), owner1, Part.file("good.pdf", pdf()), Part.file(name, pdf()));
            assertThat(response.statusCode()).as(name).isEqualTo(415);
        }
        assertThat(keys()).isEmpty();
        assertThat(attachmentRows()).isZero();
        assertThat(messages(id)).isEqualTo(1);
    }

    @Test
    void aFileThatIsNotWhatItsExtensionSaysIsRefused() throws Exception {
        long id = newRequest(owner1, "Первое");
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

        for (String name : new String[] {"photo.jpg", "photo.png", "photo.gif", "photo.webp", "doc.pdf", "doc.docx", "table.xlsx", "old.doc", "old.xls"}) {
            assertThat(multipart(messagesPath(id), owner1, Part.file(name, html)).statusCode()).as(name).isEqualTo(415);
        }
        assertThat(multipart(messagesPath(id), owner1, Part.file("text.txt", new byte[] {'a', 0, 'b'})).statusCode()).isEqualTo(415);
        assertThat(keys()).isEmpty();
    }

    @Test
    void theTypeTheClientDeclaresIsIgnored() throws Exception {
        long id = newRequest(owner1, "Первое");
        Part lying = new Part("files", "report.pdf", "text/html", pdf());

        HttpResponse<String> response = multipart(messagesPath(id), owner1, lying);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(body(response).get("attachments").get(0).get("content_type").asString()).isEqualTo("application/pdf");
    }

    // ---- the names

    @Test
    void thePathInTheNameIsDroppedAndTheKeyNeverContainsTheName() throws Exception {
        long id = newRequest(owner1, "Первое");

        HttpResponse<String> response = multipart(messagesPath(id), owner1, Part.file("..\\..\\secret\\схема.pdf", pdf()), Part.file("../../etc/passwd.txt", "hi".getBytes(StandardCharsets.UTF_8)));

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode attachments = body(response).get("attachments");
        assertThat(attachments.get(0).get("name").asString()).isEqualTo("схема.pdf");
        assertThat(attachments.get(1).get("name").asString()).isEqualTo("passwd.txt");
        assertThat(keys()).allMatch(key -> key.matches("[0-9a-f-]{36}"));
    }

    @Test
    void aLongNameIsCutTo255CharactersAndKeepsItsExtension() throws Exception {
        long id = newRequest(owner1, "Первое");

        // the name travels in a header of the multipart form, which the server reads up to about 500 bytes
        HttpResponse<String> response = multipart(messagesPath(id), owner1, Part.file("n".repeat(300) + ".pdf", pdf()));

        assertThat(response.statusCode()).isEqualTo(201);
        String name = body(response).get("attachments").get(0).get("name").asString();
        assertThat(name.codePointCount(0, name.length())).isEqualTo(255);
        assertThat(name).endsWith(".pdf");
    }

    @Test
    void aNameThatIsEmptyAfterTheCleaningIsRefused() throws Exception {
        long id = newRequest(owner1, "Первое");

        assertThat(multipart(messagesPath(id), owner1, Part.file("folder/", pdf())).statusCode()).isEqualTo(400);
        assertThat(keys()).isEmpty();
    }

    // ---- the rights

    @Test
    void aForeignRequestGetsNoFilesAndNothingIsUploadedForIt() throws Exception {
        long id = newRequest(owner1, "Первое");

        assertThat(multipart(messagesPath(id), owner2, Part.file("a.pdf", pdf())).statusCode()).isEqualTo(404);
        assertThat(multipart(messagesPath(id), resident, Part.file("a.pdf", pdf())).statusCode()).isEqualTo(403);
        assertThat(multipart(messagesPath(id), null, Part.file("a.pdf", pdf())).statusCode()).isEqualTo(401);
        assertThat(multipart(messagesPath(9999999), admin, Part.file("a.pdf", pdf())).statusCode()).isEqualTo(404);
        assertThat(keys()).isEmpty();
        assertThat(messages(id)).isEqualTo(1);
    }

    // ---- downloading

    private long[] sentFile(String name, byte[] content) throws Exception {
        long id = newRequest(owner1, "Первое");
        HttpResponse<String> response = multipart(messagesPath(id), owner1, Part.file(name, content));
        assertThat(response.statusCode()).isEqualTo(201);
        return new long[] {id, body(response).get("attachments").get(0).get("id").asLong()};
    }

    @Test
    void theAuthorDownloadsTheFileByteForByteWithSafeHeaders() throws Exception {
        byte[] content = pdf(1234);
        long[] ids = sentFile("схема дома.pdf", content);

        HttpResponse<byte[]> response = download("/api/requests/" + ids[0] + "/attachments/" + ids[1], owner1);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(content);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).isEqualTo("application/pdf");
        assertThat(response.headers().firstValue("Content-Length").orElseThrow()).isEqualTo("1234");
        String disposition = response.headers().firstValue("Content-Disposition").orElseThrow();
        assertThat(disposition).startsWith("attachment");
        assertThat(java.net.URLDecoder.decode(disposition.substring(disposition.indexOf("''") + 2), StandardCharsets.UTF_8)).isEqualTo("схема дома.pdf");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        String cache = response.headers().firstValue("Cache-Control").orElseThrow();
        assertThat(cache).contains("no-store").contains("private");
    }

    @Test
    void anAdministratorDownloadsAnyFile() throws Exception {
        byte[] content = pdf(321);
        long[] ids = sentFile("a.pdf", content);

        HttpResponse<byte[]> response = download("/api/requests/" + ids[0] + "/attachments/" + ids[1], admin);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(content);
    }

    @Test
    void anImageIsServedWithItsOwnType() throws Exception {
        long[] ids = sentFile("a.jpg", jpeg());

        HttpResponse<byte[]> response = download("/api/requests/" + ids[0] + "/attachments/" + ids[1], owner1);

        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).isEqualTo("image/jpeg");
        assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("attachment");
    }

    @Test
    void aForeignOwnerAResidentAndAnAnonymousClientGetNothing() throws Exception {
        long[] ids = sentFile("a.pdf", pdf());
        String path = "/api/requests/" + ids[0] + "/attachments/" + ids[1];

        HttpResponse<byte[]> foreign = download(path, owner2);

        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(new String(foreign.body(), StandardCharsets.ISO_8859_1)).doesNotContain("%PDF");
        assertThat(download(path, resident).statusCode()).isEqualTo(403);
        assertThat(download(path, null).statusCode()).isEqualTo(401);
    }

    @Test
    void aFileOfAnotherRequestOrAMissingFileIsNotFound() throws Exception {
        long[] first = sentFile("a.pdf", pdf());
        long other = newRequest(owner1, "Другая");

        assertThat(download("/api/requests/" + other + "/attachments/" + first[1], owner1).statusCode()).isEqualTo(404);
        assertThat(download("/api/requests/" + first[0] + "/attachments/9999999", owner1).statusCode()).isEqualTo(404);
        assertThat(download("/api/requests/9999999/attachments/" + first[1], admin).statusCode()).isEqualTo(404);
    }

    @Test
    void aLostObjectIsNotFoundAndTheOthersWork() throws Exception {
        long id = newRequest(owner1, "Первое");
        HttpResponse<String> response = multipart(messagesPath(id), owner1, Part.file("lost.pdf", pdf()), Part.file("kept.pdf", pdf(77)));
        long lost = body(response).get("attachments").get(0).get("id").asLong();
        long kept = body(response).get("attachments").get(1).get("id").asLong();
        String lostKey = jdbc.queryForObject("SELECT object_key FROM request_attachments WHERE id = ?", String.class, lost);
        s3.deleteObject(DeleteObjectRequest.builder().bucket(attachmentsBucket).key(lostKey).build());

        assertThat(download("/api/requests/" + id + "/attachments/" + lost, owner1).statusCode()).isEqualTo(404);
        assertThat(download("/api/requests/" + id + "/attachments/" + kept, owner1).statusCode()).isEqualTo(200);
    }

    @Test
    void filesCannotBeChangedOrDeletedThroughTheApi() throws Exception {
        long[] ids = sentFile("a.pdf", pdf());
        String path = "/api/requests/" + ids[0] + "/attachments/" + ids[1];

        for (String method : new String[] {"DELETE", "PUT", "PATCH", "POST"}) {
            assertThat(call(method, path, admin, "{}").statusCode()).as(method).isIn(404, 405, 415);
        }
        assertThat(download(path, owner1).statusCode()).isEqualTo(200);
        assertThat(keys()).hasSize(1);
    }

    // ---- cleaning

    @Test
    void whenTheDatabaseFailsAfterTheUploadTheObjectsAreDeletedAndNoMessageIsKept() throws Exception {
        long id = newRequest(owner1, "Первое");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION it_fail_attachment() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'the test breaks the database'; END $$ LANGUAGE plpgsql""");
        jdbc.execute("CREATE TRIGGER it_fail_attachment BEFORE INSERT ON request_attachments FOR EACH ROW EXECUTE FUNCTION it_fail_attachment()");

        HttpResponse<String> response = multipart(messagesPath(id), owner1, Part.field("text", "Не сохранится"), Part.file("a.pdf", pdf()), Part.file("b.pdf", pdf()));

        assertThat(response.statusCode()).isGreaterThanOrEqualTo(500);
        assertThat(keys()).isEmpty();
        assertThat(messages(id)).isEqualTo(1);
        assertThat(attachmentRows()).isZero();
    }

    @Test
    void whenTheDatabaseFailsForANewRequestNothingIsKept() throws Exception {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION it_fail_attachment() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'the test breaks the database'; END $$ LANGUAGE plpgsql""");
        jdbc.execute("CREATE TRIGGER it_fail_attachment BEFORE INSERT ON request_attachments FOR EACH ROW EXECUTE FUNCTION it_fail_attachment()");
        int before = jdbc.queryForObject("SELECT count(*) FROM requests", Integer.class);

        HttpResponse<String> response = multipart("/api/requests", owner1, Part.field("text", "Не сохранится"), Part.file("a.pdf", pdf()));

        assertThat(response.statusCode()).isGreaterThanOrEqualTo(500);
        assertThat(keys()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM requests", Integer.class)).isEqualTo(before);
    }
}
