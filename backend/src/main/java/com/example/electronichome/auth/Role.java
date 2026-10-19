package com.example.electronichome.auth;

/** The roles of a user; the name is stored in the database and in the {@code roles} claim of a token. */
public enum Role {
    ADMIN, OWNER, RESIDENT
}
