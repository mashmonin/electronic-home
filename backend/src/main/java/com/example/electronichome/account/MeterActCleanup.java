package com.example.electronichome.account;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Once an hour deletes the files of the acts that were uploaded and not used for a day. */
@Component
public class MeterActCleanup {

    private final MeterVerificationService service;

    MeterActCleanup(MeterVerificationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    public void run() {
        service.deleteExpiredUploads();
    }
}
