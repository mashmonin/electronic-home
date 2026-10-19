package com.example.electronichome.request;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads and writes the requests and their messages. */
@Repository
class RequestRepository {

    static final int MAX_REQUESTS = 200;
    static final int MAX_MESSAGES = 500;
    private static final int PREVIEW_LENGTH = 120;

    /** An account of the user that a request may be written for. */
    record UserAccount(long id, String number) {
    }

    private static final String ITEM_SELECT = """
            SELECT r.id, r.number, r.created_at, r.last_message_at,
                   a.number AS account_number, ad.apartment, au.login AS author_login,
                   lm.body AS last_body, lu.role AS last_role
            FROM requests r
            JOIN accounts a ON a.id = r.account_id
            JOIN addresses ad ON ad.id = a.address_id
            JOIN users au ON au.id = r.author_id
            JOIN LATERAL (SELECT m.body, m.author_id,
                                 -- the first message after the last answer of an administrator: since when the owner waits
                                 (SELECT min(w.created_at) FROM request_messages w
                                  WHERE w.request_id = r.id
                                    AND w.id > COALESCE((SELECT max(a2.id) FROM request_messages a2
                                                         JOIN users u2 ON u2.id = a2.author_id
                                                         WHERE a2.request_id = r.id AND u2.role = 'ADMIN'), 0)) AS waiting_since
                          FROM request_messages m WHERE m.request_id = r.id ORDER BY m.id DESC LIMIT 1) lm ON TRUE
            JOIN users lu ON lu.id = lm.author_id
            """;

    private final JdbcClient jdbc;

    RequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The requests the user may see whose number has the fragment (all of them for a null fragment): first those that
     * wait for an answer, the longest waiting first, then the answered ones, the newest activity first.
     */
    List<RequestResponses.Item> list(long userId, boolean admin, String fragment) {
        return jdbc.sql(ITEM_SELECT + """
                        WHERE (:admin OR r.author_id = :userId) AND (CAST(:fragment AS varchar) IS NULL OR position(:fragment IN r.number) > 0)
                        ORDER BY (lu.role = 'OWNER') DESC,
                                 CASE WHEN lu.role = 'OWNER' THEN lm.waiting_since END ASC,
                                 r.last_message_at DESC, r.id DESC
                        LIMIT %d""".formatted(MAX_REQUESTS))
                .param("admin", admin)
                .param("userId", userId)
                .param("fragment", fragment)
                .query((rs, row) -> toItem(rs))
                .list();
    }

    Optional<RequestResponses.Item> find(long id, long userId, boolean admin) {
        return jdbc.sql(ITEM_SELECT + " WHERE r.id = :id AND (:admin OR r.author_id = :userId)")
                .param("id", id)
                .param("admin", admin)
                .param("userId", userId)
                .query((rs, row) -> toItem(rs))
                .optional();
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
        return jdbc.sql("INSERT INTO requests (account_id, author_id, number) VALUES (:account, :author, :number) RETURNING id")
                .param("account", accountId)
                .param("author", authorId)
                .param("number", number)
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
        return jdbc.sql("""
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
    }

    Optional<RequestResponses.Message> message(long id, long userId) {
        return jdbc.sql("""
                        SELECT m.id, m.body, m.created_at, m.author_id, u.login, u.role
                        FROM request_messages m JOIN users u ON u.id = m.author_id WHERE m.id = :id""")
                .param("id", id)
                .query((rs, row) -> toMessage(rs, userId))
                .optional();
    }

    private static RequestResponses.Item toItem(java.sql.ResultSet rs) throws java.sql.SQLException {
        String body = rs.getString("last_body");
        String preview = body.length() <= PREVIEW_LENGTH ? body : body.substring(0, PREVIEW_LENGTH);
        return new RequestResponses.Item(
                rs.getLong("id"),
                rs.getString("number"),
                "OWNER".equals(rs.getString("last_role")),
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
                rs.getLong("author_id") == userId);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getObject(column, java.time.OffsetDateTime.class).toInstant();
    }
}
