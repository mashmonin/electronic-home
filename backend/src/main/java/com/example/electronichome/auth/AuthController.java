package com.example.electronichome.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    record LoginRequest(String login, String password) {
    }

    private final AuthService service;

    AuthController(AuthService service) {
        this.service = service;
    }

    @PostMapping("/login")
    AuthResponses.Token login(@RequestBody LoginRequest request) {
        if (request == null || isBlank(request.login()) || isBlank(request.password())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return service.login(request.login().trim(), request.password());
    }

    @GetMapping("/me")
    AuthResponses.Me me(Authentication authentication) {
        return service.me(AccountAccess.userId(authentication));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
