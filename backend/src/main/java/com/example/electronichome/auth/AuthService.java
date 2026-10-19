package com.example.electronichome.auth;

import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class AuthService {

    // Compared with when the login is unknown, so that the answer takes as long as for a known one.
    private final String dummyHash;

    private final UserDirectory users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;

    AuthService(UserDirectory users, PasswordEncoder passwordEncoder, TokenService tokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    AuthResponses.Token login(String login, String password) {
        Optional<UserDirectory.User> found = users.findByLogin(login);
        boolean matches = passwordEncoder.matches(password, found.map(UserDirectory.User::passwordHash).orElse(dummyHash));
        if (found.isEmpty() || !matches || !found.get().enabled()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        UserDirectory.User user = found.get();
        TokenService.Issued token = tokens.issue(user.id(), user.role());
        return new AuthResponses.Token(token.value(), "Bearer", token.expiresInSeconds());
    }

    AuthResponses.Me me(long userId) {
        UserDirectory.User user = users.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        List<String> accounts = user.role() == Role.ADMIN ? users.allAccountNumbers() : users.accountNumbersOf(userId);
        return new AuthResponses.Me(user.login(), user.role(), accounts);
    }
}
