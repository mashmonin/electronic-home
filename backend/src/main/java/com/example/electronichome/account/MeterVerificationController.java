package com.example.electronichome.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The change of the verification date of a meter: the administrator and the owner of the account may, a resident may not. */
@RestController
@RequestMapping("/api/accounts/{number}/meters/{meterNumber}")
class MeterVerificationController {

    private static final Logger log = LoggerFactory.getLogger(MeterVerificationController.class);

    record ChangeRequest(
            @JsonProperty("verification_date") String verificationDate,
            @JsonProperty("act_file_key") String actFileKey) {
    }

    private final MeterVerificationService service;

    MeterVerificationController(MeterVerificationService service) {
        this.service = service;
    }

    @PostMapping(value = "/verification-act-uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER') and @accountAccess.canAccess(authentication, #number)")
    MeterVerificationResponses.Uploaded upload(
            Authentication authentication,
            @PathVariable String number,
            @PathVariable String meterNumber,
            @RequestPart(value = "file", required = false) MultipartFile file) {
        return service.upload(number, meterNumber, userId(authentication), file);
    }

    // 201 when a request waits for the administrator (an owner), 200 when the date is in force (an administrator)
    @PutMapping("/verification-date")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER') and @accountAccess.canAccess(authentication, #number)")
    ResponseEntity<MeterVerificationResponses.Changed> change(
            Authentication authentication,
            @PathVariable String number,
            @PathVariable String meterNumber,
            @RequestBody ChangeRequest body) {
        boolean admin = AuthorityUtils.authorityListToSet(authentication.getAuthorities()).contains("ROLE_ADMIN");
        MeterVerificationResponses.Changed changed = service.changeDate(
                number, meterNumber, userId(authentication), admin, body.verificationDate(), body.actFileKey());
        return ResponseEntity.status(changed.applied() ? HttpStatus.OK : HttpStatus.CREATED).body(changed);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Void> conflict(DataIntegrityViolationException e) {
        log.warn("Changing the verification date conflicted with database constraints", e);
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    private static long userId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
