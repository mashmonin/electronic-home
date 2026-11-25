package com.example.electronichome.statement;

/** The file is not a statement of the charges this service understands; the text is safe to show (no personal data). */
class StatementException extends RuntimeException {

    StatementException(String message) {
        super(message);
    }
}
