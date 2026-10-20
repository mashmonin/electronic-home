package com.example.electronichome.request;

/** The state of a request; it only moves one step forward: ACCEPTED, IN_PROGRESS, DONE, CLOSED. */
enum RequestStatus {
    ACCEPTED, IN_PROGRESS, DONE, CLOSED;

    /** The only status the request may move to, or null for the last one. */
    RequestStatus next() {
        return ordinal() + 1 < values().length ? values()[ordinal() + 1] : null;
    }
}
