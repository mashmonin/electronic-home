package com.example.electronichome.account;

import org.springframework.stereotype.Component;

import com.example.electronichome.request.VerificationApplier;

/** Lets the requests put the date of a meter in force without knowing about the acts. */
@Component
class MeterActApplier implements VerificationApplier {

    private final MeterVerificationService service;

    MeterActApplier(MeterVerificationService service) {
        this.service = service;
    }

    @Override
    public void apply(long requestId) {
        service.apply(requestId);
    }
}
