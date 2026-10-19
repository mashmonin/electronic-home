package com.example.electronichome.request;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Requests of the owners and the chats under them. A resident has none: every method refuses him. */
@RestController
@RequestMapping("/api/requests")
class RequestController {

    // the number is given by the server; a subject or a number in the body is ignored
    record NewRequest(String text, @com.fasterxml.jackson.annotation.JsonProperty("account_number") String accountNumber) {
    }

    record NewMessage(String text) {
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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OWNER')")
    RequestResponses.Item create(Authentication authentication, @RequestBody NewRequest body) {
        return service.create(caller(authentication), body.text(), body.accountNumber());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    RequestResponses.Item get(Authentication authentication, @PathVariable long id) {
        return service.get(caller(authentication), id);
    }

    @GetMapping("/{id}/messages")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    RequestResponses.Messages messages(
            Authentication authentication, @PathVariable long id, @RequestParam(required = false) String after) {
        return service.messages(caller(authentication), id, after);
    }

    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    RequestResponses.Message addMessage(Authentication authentication, @PathVariable long id, @RequestBody NewMessage body) {
        return service.addMessage(caller(authentication), id, body.text());
    }

    private static RequestService.Caller caller(Authentication authentication) {
        boolean admin = AuthorityUtils.authorityListToSet(authentication.getAuthorities()).contains("ROLE_ADMIN");
        return new RequestService.Caller(Long.parseLong(authentication.getName()), admin);
    }
}
