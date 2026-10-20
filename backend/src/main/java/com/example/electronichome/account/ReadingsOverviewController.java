package com.example.electronichome.account;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The readings of all the apartments by months, for the administrator only; it reads and changes nothing. */
@RestController
@RequestMapping("/api/readings")
class ReadingsOverviewController {

    private final ReadingsOverviewService service;

    ReadingsOverviewController(ReadingsOverviewService service) {
        this.service = service;
    }

    @GetMapping("/periods")
    @PreAuthorize("hasRole('ADMIN')")
    ReadingsOverviewResponses.Periods periods() {
        return service.periods();
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    ReadingsOverviewResponses.Month month(@RequestParam(required = false) String month) {
        return service.month(month);
    }
}
