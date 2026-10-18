package com.example.electronichome.account;

import java.util.List;

/** Thrown when a readings request breaks the rules; nothing of the request is saved. */
class InvalidReadingsException extends RuntimeException {

    private final transient List<ReadingError> errors;

    InvalidReadingsException(List<ReadingError> errors) {
        super("Invalid readings request: " + errors);
        this.errors = List.copyOf(errors);
    }

    List<ReadingError> errors() {
        return errors;
    }
}
