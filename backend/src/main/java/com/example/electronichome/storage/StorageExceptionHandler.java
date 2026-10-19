package com.example.electronichome.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A storage that does not answer is 503 without details; the cause goes to the log. */
@RestControllerAdvice
class StorageExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(StorageExceptionHandler.class);

    @ExceptionHandler(StorageUnavailableException.class)
    ResponseEntity<Void> unavailable(StorageUnavailableException e) {
        log.error("The object storage is not available", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
