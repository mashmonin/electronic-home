package com.example.electronichome.request;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads and writes the requests and their messages. */
@Repository
class RequestRepository {

    static final int PAGE_SIZE = 5;
    static final int MAX_MESSAGES = 500;
    private static final int PREVIEW_LENGTH = 120;

    /** An account of the user that a request may be written for. */
    record UserAccount(long id, String number) {
    }

    private static final String ITEM_SELECT = """
            SELECT r.id, r.number, r.kind, r.status, r.created_at, r.last_message_at,
                   a.number AS account_number, ad.apartment, au.login AS author_login,
                   lm.body AS last_body, lm.last_files, lm.last_file_name, lu.role AS last_role
            FROM requests r
            JOIN accounts a ON a.id = r.account_id
            JOIN addresses ad ON ad.id = a.address_id
            JOIN users au ON au.id = r.author_id
            JOIN LATERAL (SELECT m.body, m.author_id,
                                 (SELECT count(*) FROM request_attachments x WHERE x.message_id = m.id) AS last_files,
                                 (SELECT min(x.file_name) FROM request_attachments x WHERE x.message_id = m.id) AS last_file_name
                          FROM request_messages m WHERE m.request_id = r.id ORDER BY m.id DESC LIMIT 1) lm ON TRUE
            JOIN users lu ON lu.id = lm.author_id
            """;

    private final JdbcClient jdbc;

    RequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One page of the requests the user may see whose number has the fragment (all of them for a null fragment): by the
     * status (accepted, in work, done), inside a status the earliest created first. The pages are {@value #PAGE_SIZE}
     * requests long, the first one is 1.
     */
    List<RequestResponses.Item> list(long userId, boolean admin, String fragment, int page) {
        return jdbc.sql(ITEM_SELECT + """
                        WHERE (:admin OR r.author_id = :userId) AND (CAST(:fragment AS varchar) IS NULL OR position(:fragment IN r.number) > 0)
                        ORDER BY CASE r.status WHEN 'ACCEPTED' THEN 0 WHEN 'IN_PROGRESS' THEN 1 ELSE 2 END,
                                 r.created_at ASC, r.id ASC
                        LIMIT %d OFFSET %d""".formatted(PAGE_SIZE, (long) (page - 1) * PAGE_SIZE))
                .param("admin", admin)
                .param("userId", userId)
                .param("fragment", fragment)
                .query((rs, row) -> toItem(rs))
                .list();
    }

    /** How many requests the user may see with the fragment in the number: for the pages. */
    long count(long userId, boolean admin, String fragment) {
        return jdbc.sql("""
                        SELECT count(*) FROM requests r
                        WHERE (:admin OR r.author_id = :userId) AND (CAST(:fragment AS varchar) IS NULL OR position(:fragment IN r.number) > 0)""")
                .param("admin", admin)
                .param("userId", userId)
                .param("fragment", fragment)
                .query(Long.class)
                .single();
    }

    Optional<RequestResponses.Item> find(long id, long userId, boolean admin) {
        return jdbc.sql(ITEM_SELECT + " WHERE r.id = :id AND (:admin OR r.author_id = :userId)")
                .param("id", id)
                .param("admin", admin)
                .param("userId", userId)
                .query((rs, row) -> toItem(rs))
                .optional();
    }

    /** Moves the request from one status to the next in one statement, so that two callers cannot both pass; false when it was not in {@code from}. */
    boolean changeStatus(long id, RequestStatus from, RequestStatus to) {
        return jdbc.sql("UPDATE requests SET status = :to WHERE id = :id AND status = :from")
                .param("id", id)
                .param("from", from.name())
                .param("to", to.name())
                .update() == 1;
    }

    List<UserAccount> accountsOf(long userId) {
        return jdbc.sql("""
                        SELECT a.id, a.number FROM user_accounts ua JOIN accounts a ON a.id = ua.account_id
                        WHERE ua.user_id = :userId ORDER BY a.number""")
                .param("userId", userId)
                .query((rs, row) -> new UserAccount(rs.getLong("id"), rs.getString("number")))
                .list();
    }

    long insertRequest(long accountId, long authorId, String number) {
        return insertRequest(accountId, authorId, number, RequestKind.GENERAL);
    }

    // the status is the default of the column: ACCEPTED
    long insertRequest(long accountId, long authorId, String number, RequestKind kind) {
        return jdbc.sql("INSERT INTO requests (account_id, author_id, number, kind) VALUES (:account, :author, :number, :kind) RETURNING id")
                .param("account", accountId)
                .param("author", authorId)
                .param("number", number)
                .param("kind", kind.name())
                .query(Long.class)
                .single();
    }

    /** Adds the message and moves the time of the last message of the request to it. */
    long insertMessage(long requestId, long authorId, String body) {
        long id = jdbc.sql("INSERT INTO request_messages (request_id, author_id, body) VALUES (:request, :author, :body) RETURNING id")
                .param("request", requestId)
                .param("author", authorId)
                .param("body", body)
                .query(Long.class)
                .single();
        jdbc.sql("UPDATE requests SET last_message_at = (SELECT created_at FROM request_messages WHERE id = :id) WHERE id = :request")
                .param("id", id)
                .param("request", requestId)
                .update();
        return id;
    }

    /** The newest {@value #MAX_MESSAGES} messages after the given one, oldest first. */
    List<RequestResponses.Message> messages(long requestId, long after, long userId) {
        List<RequestResponses.Message> messages = jdbc.sql("""
                        SELECT * FROM (
                            SELECT m.id, m.body, m.created_at, m.author_id, u.login, u.role
                            FROM request_messages m JOIN users u ON u.id = m.author_id
                            WHERE m.request_id = :request AND m.id > :after
                            ORDER BY m.id DESC LIMIT %d) newest
                        ORDER BY id""".formatted(MAX_MESSAGES))
                .param("request", requestId)
                .param("after", after)
                .query((rs, row) -> toMessage(rs, userId))
                .list();
        return withAttachments(messages);
    }

    Optional<RequestResponses.Message> message(long id, long userId) {
        return jdbc.sql("""
                        SELECT m.id, m.body, m.created_at, m.author_id, u.login, u.role
                        FROM request_messages m JOIN users u ON u.id = m.author_id WHERE m.id = :id""")
                .param("id", id)
                .query((rs, row) -> toMessage(rs, userId))
                .optional()
                .map(message -> withAttachments(List.of(message)).get(0));
    }

    /** A file that is attached to a message; the key is the object in the bucket of the attachments. */
    record AttachmentFile(String key, String name, String contentType, long size) {
    }

    void insertAttachment(long messageId, AttachmentStorage.Stored file) {
        jdbc.sql("""
                        INSERT INTO request_attachments (message_id, object_key, file_name, content_type, size_bytes, sha256)
                        VALUES (:message, :key, :name, :type, :size, :sha)""")
                .param("message", messageId)
                .param("key", file.key())
                .param("name", file.name())
                .param("type", file.contentType())
                .param("size", file.size())
                .param("sha", file.sha256())
                .update();
    }

    /** The attachment of the request, if the user may see the request (the author and the administrator may). */
    Optional<AttachmentFile> findAttachment(long requestId, long attachmentId, long userId, boolean admin) {
        return jdbc.sql("""
                        SELECT a.object_key, a.file_name, a.content_type, a.size_bytes
                        FROM request_attachments a
                        JOIN request_messages m ON m.id = a.message_id
                        JOIN requests r ON r.id = m.request_id
                        WHERE a.id = :attachment AND r.id = :request AND (:admin OR r.author_id = :userId)""")
                .param("attachment", attachmentId)
                .param("request", requestId)
                .param("admin", admin)
                .param("userId", userId)
                .query((rs, row) -> new AttachmentFile(
                        rs.getString("object_key"), rs.getString("file_name"), rs.getString("content_type"), rs.getLong("size_bytes")))
                .optional();
    }

    private record Row(long messageId, RequestResponses.Attachment attachment) {
    }

    // One query for the files of all the messages of the answer.
    private List<RequestResponses.Message> withAttachments(List<RequestResponses.Message> messages) {
        if (messages.isEmpty()) {
            return messages;
        }
        Map<Long, List<RequestResponses.Attachment>> byMessage = new HashMap<>();
        jdbc.sql("SELECT id, message_id, file_name, size_bytes, content_type FROM request_attachments WHERE message_id IN (:ids) ORDER BY id")
                .param("ids", messages.stream().map(RequestResponses.Message::id).toList())
                .query((rs, row) -> new Row(rs.getLong("message_id"), new RequestResponses.Attachment(
                        rs.getLong("id"), rs.getString("file_name"), rs.getLong("size_bytes"), rs.getString("content_type"))))
                .list()
                .forEach(row -> byMessage.computeIfAbsent(row.messageId(), id -> new ArrayList<>()).add(row.attachment()));
        return messages.stream()
                .map(m -> new RequestResponses.Message(
                        m.id(), m.text(), m.createdAt(), m.author(), m.mine(), byMessage.getOrDefault(m.id(), List.of())))
                .toList();
    }

    private static RequestResponses.Item toItem(java.sql.ResultSet rs) throws java.sql.SQLException {
        String body = rs.getString("last_body");
        if (body.isBlank()) {
            // a message that is only files: name the file, or say how many there are
            int files = rs.getInt("last_files");
            body = files == 1 ? "Вложение: " + rs.getString("last_file_name") : "Вложений: " + files;
        }
        String preview = body.length() <= PREVIEW_LENGTH ? body : body.substring(0, PREVIEW_LENGTH);
        return new RequestResponses.Item(
                rs.getLong("id"),
                rs.getString("number"),
                rs.getString("kind"),
                rs.getString("status"),
                "OWNER".equals(rs.getString("last_role")) && !"DONE".equals(rs.getString("status")),
                instant(rs, "created_at"),
                instant(rs, "last_message_at"),
                new RequestResponses.LastMessage(preview, rs.getString("last_role")),
                new RequestResponses.AccountInfo(rs.getString("account_number"), rs.getString("apartment")),
                new RequestResponses.Author(rs.getString("author_login")));
    }

    private static RequestResponses.Message toMessage(java.sql.ResultSet rs, long userId) throws java.sql.SQLException {
        return new RequestResponses.Message(
                rs.getLong("id"),
                rs.getString("body"),
                instant(rs, "created_at"),
                new RequestResponses.MessageAuthor(rs.getString("login"), rs.getString("role")),
                rs.getLong("author_id") == userId,
                List.of());
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getObject(column, java.time.OffsetDateTime.class).toInstant();
    }
}
