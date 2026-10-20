package com.example.electronichome.request;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Requests of the owners and the chats under them. A resident has none: every method refuses him. */
@RestController
@RequestMapping("/api/requests")
class RequestController {

    // the number is given by the server; a subject or a number in the body is ignored
    record NewRequest(String text, @com.fasterxml.jackson.annotation.JsonProperty("account_number") String accountNumber) {
    }

    record NewMessage(String text) {
    }

    record NewStatus(String status) {
    }

    private final RequestService service;

    RequestController(RequestService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    RequestResponses.Items list(Authentication authentication, @RequestParam(required = false) String q) {
        return new RequestResponses.Items(service.list(caller(authentication), q));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OWNER')")
    RequestResponses.Item create(Authentication authentication, @RequestBody NewRequest body) {
        return service.create(caller(authentication), body.text(), body.accountNumber(), List.of());
    }

    // the same with files: the parts "text", "account_number" and "files" of a multipart form
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OWNER')")
    RequestResponses.Item createWithFiles(
            Authentication authentication,
            @RequestParam(value = "text", required = false) String text,
            @RequestParam(value = "account_number", required = false) String accountNumber,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        return service.create(caller(authentication), text, accountNumber, files);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    RequestResponses.Item get(Authentication authentication, @PathVariable long id) {
        return service.get(caller(authentication), id);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    RequestResponses.Item changeStatus(Authentication authentication, @PathVariable long id, @RequestBody NewStatus body) {
        return service.changeStatus(caller(authentication), id, body.status());
    }

    @GetMapping("/{id}/messages")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    RequestResponses.Messages messages(
            Authentication authentication, @PathVariable long id, @RequestParam(required = false) String after) {
        return service.messages(caller(authentication), id, after);
    }

    @PostMapping(value = "/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    RequestResponses.Message addMessage(Authentication authentication, @PathVariable long id, @RequestBody NewMessage body) {
        return service.addMessage(caller(authentication), id, body.text(), List.of());
    }

    // the same with files: the parts "text" and "files" of a multipart form
    @PostMapping(value = "/{id}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    RequestResponses.Message addMessageWithFiles(
            Authentication authentication,
            @PathVariable long id,
            @RequestParam(value = "text", required = false) String text,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        return service.addMessage(caller(authentication), id, text, files);
    }

    // The file goes through the backend, so the rights of the request are checked; the storage has no public address.
    @GetMapping("/{id}/attachments/{attachmentId}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    ResponseEntity<InputStreamResource> download(Authentication authentication, @PathVariable long id, @PathVariable long attachmentId) {
        RequestService.DownloadedAttachment file = service.download(caller(authentication), id, attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.name(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(new InputStreamResource(file.content()));
    }

    private static RequestService.Caller caller(Authentication authentication) {
        boolean admin = AuthorityUtils.authorityListToSet(authentication.getAuthorities()).contains("ROLE_ADMIN");
        return new RequestService.Caller(Long.parseLong(authentication.getName()), admin);
    }
}
