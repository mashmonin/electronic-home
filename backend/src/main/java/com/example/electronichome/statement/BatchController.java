package com.example.electronichome.statement;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** The upload of the statement of the charges and the progress of the making of the receipts; for administrators only. */
@RestController
@RequestMapping("/api/receipt-batches")
@PreAuthorize("hasRole('ADMIN')")
class BatchController {

    private final BatchService service;

    BatchController(BatchService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    BatchResponses.Batch upload(Authentication authentication, @RequestParam(value = "file", required = false) MultipartFile file) throws IOException {
        return service.create(file, Long.parseLong(authentication.getName()));
    }

    // the reason of a refusal is a text for the administrator (it never carries data of the sheet)
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<BatchResponses.Message> refused(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(new BatchResponses.Message(e.getReason()));
    }

    @GetMapping
    BatchResponses.Batches list() {
        return service.list();
    }

    @GetMapping("/{id}")
    BatchResponses.Detail get(@PathVariable long id) {
        return service.get(id);
    }
}
