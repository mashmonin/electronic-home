package com.example.electronichome.account;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
class ReadingController {

    private static final Logger log = LoggerFactory.getLogger(ReadingController.class);

    private final ReadingService service;

    ReadingController(ReadingService service) {
        this.service = service;
    }

    @PostMapping("/{number}/readings")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@accountAccess.canAccess(authentication, #number)")
    ReadingsResponse save(@PathVariable String number, @RequestBody ReadingsRequest request) {
        return service.save(number, request);
    }

    record Errors(List<ReadingError> errors) {
    }

    @ExceptionHandler(InvalidReadingsException.class)
    ResponseEntity<Errors> invalid(InvalidReadingsException e) {
        return ResponseEntity.badRequest().body(new Errors(e.errors()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Void> conflict(DataIntegrityViolationException e) {
        log.warn("Saving readings conflicted with database constraints", e);
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
