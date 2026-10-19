package com.example.electronichome.request;

import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The rules of the requests: an owner works only with his own, an administrator with all of them; nobody closes them. A request of another
 * owner is as good as missing ({@code 404}), so that the numbers cannot be tried one after another.
 */
@Service
class RequestService {

    static final int MAX_TEXT = 2000;
    private static final int MAX_FRAGMENT = 64;

    /** Who asks: the user of the token and whether he is an administrator. */
    record Caller(long id, boolean admin) {
    }

    private final RequestRepository requests;
    private final RequestNumbers numbers;

    RequestService(RequestRepository requests, RequestNumbers numbers) {
        this.requests = requests;
        this.numbers = numbers;
    }

    @Transactional(readOnly = true)
    List<RequestResponses.Item> list(Caller caller, String query) {
        return requests.list(caller.id(), caller.admin(), fragmentOf(query));
    }

    @Transactional(readOnly = true)
    RequestResponses.Item get(Caller caller, long id) {
        return requests.find(id, caller.id(), caller.admin()).orElseThrow(this::notFound);
    }

    @Transactional
    RequestResponses.Item create(Caller caller, String text, String accountNumber) {
        String cleanText = clean(text, MAX_TEXT);
        List<RequestRepository.UserAccount> accounts = requests.accountsOf(caller.id());
        if (accounts.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No account is linked to the user");
        }
        RequestRepository.UserAccount account = accountNumber == null
                ? accounts.get(0)
                : accounts.stream()
                        .filter(candidate -> candidate.number().equals(accountNumber))
                        .findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        long id = requests.insertRequest(account.id(), caller.id(), numbers.next());
        requests.insertMessage(id, caller.id(), cleanText);
        return requests.find(id, caller.id(), caller.admin()).orElseThrow();
    }

    @Transactional(readOnly = true)
    RequestResponses.Messages messages(Caller caller, long requestId, String after) {
        long afterId = 0;
        if (after != null) {
            try {
                afterId = Long.parseLong(after);
            } catch (NumberFormatException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "after must be a number");
            }
        }
        requests.find(requestId, caller.id(), caller.admin()).orElseThrow(this::notFound);
        return new RequestResponses.Messages(requests.messages(requestId, afterId, caller.id()));
    }

    @Transactional
    RequestResponses.Message addMessage(Caller caller, long requestId, String text) {
        String cleanText = clean(text, MAX_TEXT);
        requests.find(requestId, caller.id(), caller.admin()).orElseThrow(this::notFound);
        long id = requests.insertMessage(requestId, caller.id(), cleanText);
        return requests.message(id, caller.id()).orElseThrow();
    }

    /**
     * The fragment of a number to look for: trimmed, upper case, with the Latin O written as the Cyrillic one the numbers
     * have; null (no search) for an empty one.
     */
    static String fragmentOf(String query) {
        if (query == null) {
            return null;
        }
        String fragment = query.strip();
        if (fragment.isEmpty()) {
            return null;
        }
        if (fragment.length() > MAX_FRAGMENT) {
            fragment = fragment.substring(0, MAX_FRAGMENT);
        }
        return fragment.toUpperCase(Locale.ROOT).replace('O', '\u041E');
    }

    // Trimmed; not empty and not longer than the limit, otherwise 400.
    private static String clean(String value, int max) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty() || trimmed.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The text must have 1 to " + max + " characters");
        }
        return trimmed;
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
