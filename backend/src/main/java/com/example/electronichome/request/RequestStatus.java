package com.example.electronichome.request;

/**
 * The state of a request: ACCEPTED, IN_PROGRESS, DONE. The administrator moves it forward; DONE is his last step. The
 * author of the request returns it from DONE to IN_PROGRESS with a message.
 */
enum RequestStatus {
    ACCEPTED, IN_PROGRESS, DONE;

    /** The only status the administrator may move the request to, or null for the last one. */
    RequestStatus next() {
        return ordinal() + 1 < values().length ? values()[ordinal() + 1] : null;
    }

    /** Whether the user may write into a request in this status: the author before DONE, the administrator while it is in work. */
    boolean allowsMessagesOf(boolean admin) {
        return switch (this) {
            case ACCEPTED -> !admin;
            case IN_PROGRESS -> true;
            case DONE -> false;
        };
    }
}
