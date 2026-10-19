package com.example.electronichome.account;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
class AccountController {

    private final AccountService service;

    AccountController(AccountService service) {
        this.service = service;
    }

    @GetMapping("/{number}")
    @PreAuthorize("@accountAccess.canAccess(authentication, #number)")
    AccountResponse get(@PathVariable String number) {
        return service.getByNumber(number);
    }
}
