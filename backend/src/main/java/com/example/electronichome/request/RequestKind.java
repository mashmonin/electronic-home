package com.example.electronichome.request;

/** GENERAL: written by an owner; VERIFICATION: made by the system when an owner changes the verification date of a meter. */
enum RequestKind {
    GENERAL("\u041E"), VERIFICATION("\u041F");

    private final String letter;

    RequestKind(String letter) {
        this.letter = letter;
    }

    /** The Cyrillic letter the numbers of this kind begin with. */
    String letter() {
        return letter;
    }
}
