package com.example.electronichome.storage;

/** The object storage did not answer or refused the request; the files cannot be read or written now. */
public class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
