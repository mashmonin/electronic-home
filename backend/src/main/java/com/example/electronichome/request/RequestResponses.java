package com.example.electronichome.request;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Bodies of the answers of {@code /api/requests}; no storage fields (such as {@code is_test}) in them. */
final class RequestResponses {

    private RequestResponses() {
    }

    record LastMessage(String text, @JsonProperty("author_role") String authorRole) {
    }

    record AccountInfo(String number, String apartment) {
    }

    record Author(String login) {
    }

    record Item(
            long id,
            String number,
            @JsonProperty("awaiting_reply") boolean awaitingReply,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("last_message_at") Instant lastMessageAt,
            @JsonProperty("last_message") LastMessage lastMessage,
            AccountInfo account,
            Author author) {
    }

    record Items(List<Item> requests) {
    }

    record MessageAuthor(String login, String role) {
    }

    record Message(
            long id,
            String text,
            @JsonProperty("created_at") Instant createdAt,
            MessageAuthor author,
            boolean mine,
            List<Attachment> attachments) {
    }

    record Attachment(long id, String name, long size, @JsonProperty("content_type") String contentType) {
    }

    record Messages(List<Message> messages) {
    }
}
