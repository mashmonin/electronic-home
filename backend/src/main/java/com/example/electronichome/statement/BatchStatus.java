package com.example.electronichome.statement;

/** The states of a batch and of its items, and the reasons of a skip or a failure. */
final class BatchStatus {

    private BatchStatus() {
    }

    static final String QUEUED = "QUEUED";
    static final String RUNNING = "RUNNING";
    static final String DONE = "DONE";
    static final String FAILED = "FAILED";

    static final String CREATED = "CREATED";
    static final String REPLACED = "REPLACED";
    static final String SKIPPED = "SKIPPED";

    static final String ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";
    static final String MISSING_PART = "MISSING_PART";
    static final String PDF_ERROR = "PDF_ERROR";
    static final String PUBLISH_ERROR = "PUBLISH_ERROR";
    static final String INTERRUPTED = "INTERRUPTED";

    /** The text of a reason for the screen. */
    static String reasonText(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case ACCOUNT_NOT_FOUND -> "Лицевой счёт не найден";
            case MISSING_PART -> "В ведомости нет одной из двух квитанций счёта";
            case PDF_ERROR -> "Не удалось сформировать PDF";
            case PUBLISH_ERROR -> "Не удалось сохранить квитанцию";
            case INTERRUPTED -> "Формирование прервано перезапуском приложения";
            default -> code;
        };
    }
}
