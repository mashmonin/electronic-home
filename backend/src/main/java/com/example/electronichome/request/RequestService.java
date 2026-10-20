package com.example.electronichome.request;

import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * The rules of the requests: an owner works only with his own, an administrator with all of them and moves them through the statuses. A request of another
 * owner is as good as missing ({@code 404}), so that the numbers cannot be tried one after another.
 */
@Service
class RequestService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RequestService.class);

    static final int MAX_TEXT = 2000;
    static final String DONE_MESSAGE = "Благодарим за обращение, заявка выполнена.";
    private static final int MAX_FRAGMENT = 64;

    /** Who asks: the user of the token and whether he is an administrator. */
    record Caller(long id, boolean admin) {
    }

    private final RequestRepository requests;
    private final RequestNumbers numbers;
    private final AttachmentStorage attachments;
    private final TransactionTemplate transaction;
    private final VerificationApplier verifications;

    RequestService(
            RequestRepository requests,
            RequestNumbers numbers,
            AttachmentStorage attachments,
            PlatformTransactionManager transactions,
            VerificationApplier verifications) {
        this.verifications = verifications;
        this.requests = requests;
        this.numbers = numbers;
        this.attachments = attachments;
        this.transaction = new TransactionTemplate(transactions);
    }

    @Transactional(readOnly = true)
    RequestResponses.Items list(Caller caller, String query, String page) {
        int number = 1;
        if (page != null) {
            try {
                number = Integer.parseInt(page.strip());
            } catch (NumberFormatException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be a number");
            }
            if (number < 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page starts from 1");
            }
        }
        String fragment = fragmentOf(query);
        return new RequestResponses.Items(
                requests.list(caller.id(), caller.admin(), fragment, number),
                number,
                RequestRepository.PAGE_SIZE,
                requests.count(caller.id(), caller.admin(), fragment));
    }

    @Transactional(readOnly = true)
    RequestResponses.Item get(Caller caller, long id) {
        return requests.find(id, caller.id(), caller.admin()).orElseThrow(this::notFound);
    }

    /**
     * Creates the request with its first message. The files are written to the storage before the transaction, so the
     * database is not held while they travel; when the transaction fails, they are deleted.
     */
    RequestResponses.Item create(Caller caller, String text, String accountNumber, List<MultipartFile> files) {
        List<MultipartFile> parts = AttachmentStorage.realFiles(files);
        String cleanText = optionalText(text, parts.isEmpty());
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
        List<AttachmentStorage.Stored> stored = attachments.store(parts);
        try {
            return transaction.execute(status -> {
                long id = requests.insertRequest(account.id(), caller.id(), numbers.next());
                long messageId = requests.insertMessage(id, caller.id(), cleanText);
                stored.forEach(file -> requests.insertAttachment(messageId, file));
                return requests.find(id, caller.id(), caller.admin()).orElseThrow();
            });
        } catch (RuntimeException e) {
            attachments.deleteQuietly(stored);
            throw e;
        }
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

    RequestResponses.Message addMessage(Caller caller, long requestId, String text, List<MultipartFile> files) {
        List<MultipartFile> parts = AttachmentStorage.realFiles(files);
        String cleanText = optionalText(text, parts.isEmpty());
        RequestResponses.Item request = requests.find(requestId, caller.id(), caller.admin()).orElseThrow(this::notFound);
        if (!RequestStatus.valueOf(request.status()).allowsMessagesOf(caller.admin())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nobody writes into the request in this status");
        }
        List<AttachmentStorage.Stored> stored = attachments.store(parts);
        try {
            return transaction.execute(status -> {
                long id = requests.insertMessage(requestId, caller.id(), cleanText);
                stored.forEach(file -> requests.insertAttachment(id, file));
                return requests.message(id, caller.id()).orElseThrow();
            });
        } catch (RuntimeException e) {
            attachments.deleteQuietly(stored);
            throw e;
        }
    }

    /**
     * Moves the request one step forward (administrator only, see the controller). The step ACCEPTED to IN_PROGRESS of a
     * request about a verification also puts the new date of the meter in force, in the same transaction.
     */
    @Transactional
    RequestResponses.Item changeStatus(Caller caller, long id, String status) {
        RequestStatus wanted;
        try {
            wanted = RequestStatus.valueOf(status == null ? "" : status);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status");
        }
        RequestResponses.Item request = requests.find(id, caller.id(), caller.admin()).orElseThrow(this::notFound);
        RequestStatus current = RequestStatus.valueOf(request.status());
        if (current.next() != wanted) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The status moves one step forward only");
        }
        if (!requests.changeStatus(id, current, wanted)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The status has just been changed");
        }
        if (wanted == RequestStatus.IN_PROGRESS && RequestKind.VERIFICATION.name().equals(request.kind())) {
            verifications.apply(id);
        }
        if (wanted == RequestStatus.DONE) {
            // the thanks go to the chat with the status: when the message is not saved, the status is not changed either
            requests.insertMessage(id, caller.id(), DONE_MESSAGE);
        }
        return requests.find(id, caller.id(), caller.admin()).orElseThrow();
    }

    /**
     * The author returns a request that is DONE to the work, with a message that is required: the status and the message
     * are saved in one transaction, the files are written before it and deleted when it fails.
     */
    RequestResponses.Reopened reopen(Caller caller, long requestId, String text, List<MultipartFile> files) {
        List<MultipartFile> parts = AttachmentStorage.realFiles(files);
        String cleanText = clean(text, MAX_TEXT);
        RequestResponses.Item request = requests.find(requestId, caller.id(), caller.admin()).orElseThrow(this::notFound);
        if (!RequestStatus.DONE.name().equals(request.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a request that is done is returned to the work");
        }
        List<AttachmentStorage.Stored> stored = attachments.store(parts);
        try {
            return transaction.execute(status -> {
                if (!requests.changeStatus(requestId, RequestStatus.DONE, RequestStatus.IN_PROGRESS)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "The status has just been changed");
                }
                long id = requests.insertMessage(requestId, caller.id(), cleanText);
                stored.forEach(file -> requests.insertAttachment(id, file));
                return new RequestResponses.Reopened(
                        requests.find(requestId, caller.id(), caller.admin()).orElseThrow(), requests.message(id, caller.id()).orElseThrow());
            });
        } catch (RuntimeException e) {
            attachments.deleteQuietly(stored);
            throw e;
        }
    }

    /** The content of the file attached to a message of the request, or 404 for a request or a file the user cannot see. */
    DownloadedAttachment download(Caller caller, long requestId, long attachmentId) {
        RequestRepository.AttachmentFile file = requests.findAttachment(requestId, attachmentId, caller.id(), caller.admin())
                .orElseThrow(this::notFound);
        java.io.InputStream content = attachments.open(file.key()).orElse(null);
        if (content == null) {
            log.error("The file of the attachment {} of the request {} is missing in the storage", attachmentId, requestId);
            throw notFound();
        }
        return new DownloadedAttachment(file.name(), file.contentType(), file.size(), content);
    }

    /** A file to send to the client; the caller closes the content. */
    record DownloadedAttachment(String name, String contentType, long size, java.io.InputStream content) {
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

    // The text of a message: it may be missing only when there are files, and is never longer than the limit.
    private static String optionalText(String value, boolean withoutFiles) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty() && !withoutFiles) {
            return "";
        }
        return clean(trimmed, MAX_TEXT);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
