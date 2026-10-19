package com.example.electronichome.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.stereotype.Component;

/** Decides whether the signed-in user may work with a personal account; used from {@code @PreAuthorize}. */
@Component
public class AccountAccess {

    private final UserDirectory users;

    AccountAccess(UserDirectory users) {
        this.users = users;
    }

    public boolean canAccess(Authentication authentication, String accountNumber) {
        if (AuthorityUtils.authorityListToSet(authentication.getAuthorities()).contains("ROLE_ADMIN")) {
            return true;
        }
        return users.isLinked(userId(authentication), accountNumber);
    }

    static long userId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
