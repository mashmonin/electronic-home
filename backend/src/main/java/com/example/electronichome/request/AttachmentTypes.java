package com.example.electronichome.request;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Which files may be attached to a message: the extension decides the type that is given to the file, and the beginning
 * of the file must be what that format begins with. The type the client declares is never used. SVG and HTML are not
 * allowed (they can carry scripts). The check of the beginning is not an antivirus.
 */
final class AttachmentTypes {

    static final int MAX_NAME = 255;

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("txt", "text/plain; charset=utf-8"));

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] GIF87 = "GIF87a".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] GIF89 = "GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] PDF = "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] ZIP = {'P', 'K', 0x03, 0x04};
    private static final byte[] COMPOUND = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    private AttachmentTypes() {
    }

    /** The type for the extension (lower case, without the dot), or empty when such files are not allowed. */
    static Optional<String> contentTypeFor(String extension) {
        return Optional.ofNullable(TYPES.get(extension));
    }

    /** The extension of the name in lower case without the dot, or an empty string when there is none. */
    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * The name for showing: without the path, the control and the invisible formatting characters, trimmed and at most
     * {@value #MAX_NAME} characters long (a longer name is cut before its extension, which stays). Null when nothing is
     * left of the name.
     */
    static String cleanName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.substring(Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\')) + 1);
        StringBuilder clean = new StringBuilder();
        name.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint) && Character.getType(codePoint) != Character.FORMAT)
                .forEach(clean::appendCodePoint);
        String result = clean.toString().strip();
        if (result.isEmpty()) {
            return null;
        }
        if (result.codePointCount(0, result.length()) <= MAX_NAME) {
            return result;
        }
        int dot = result.lastIndexOf('.');
        String extension = dot > 0 && result.length() - dot <= 16 ? result.substring(dot) : "";
        String base = result.substring(0, result.length() - extension.length());
        int keep = MAX_NAME - extension.codePointCount(0, extension.length());
        return base.substring(0, base.offsetByCodePoints(0, Math.min(keep, base.codePointCount(0, base.length())))) + extension;
    }

    /** Whether the beginning of the file is that of the format of the extension. */
    static boolean looksLike(String extension, byte[] head) {
        return switch (extension) {
            case "jpg", "jpeg" -> startsWith(head, JPEG);
            case "png" -> startsWith(head, PNG);
            case "gif" -> startsWith(head, GIF87) || startsWith(head, GIF89);
            case "webp" -> head.length >= 12 && startsWith(head, new byte[] {'R', 'I', 'F', 'F'})
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
            case "pdf" -> startsWith(head, PDF);
            case "docx", "xlsx" -> startsWith(head, ZIP);
            case "doc", "xls" -> startsWith(head, COMPOUND);
            case "txt" -> noNulBytes(head);
            default -> false;
        };
    }

    private static boolean startsWith(byte[] head, byte[] prefix) {
        if (head.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (head[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean noNulBytes(byte[] head) {
        for (byte b : head) {
            if (b == 0) {
                return false;
            }
        }
        return true;
    }
}
