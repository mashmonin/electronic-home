package com.example.electronichome.request;

/**
 * Puts the date of a meter in force when the administrator confirms the request about its verification. Implemented by
 * the module of the meters; it runs in the transaction of the change of the status and throws when it cannot, so that
 * the status stays as it was.
 */
public interface VerificationApplier {

    void apply(long requestId);
}
