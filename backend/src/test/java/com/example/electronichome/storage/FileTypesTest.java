package com.example.electronichome.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class FileTypesTest {

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void theTypeComesFromTheExtensionAndIsOneOfTheAllowedOnes() {
        assertThat(FileTypes.contentTypeFor("jpg")).contains("image/jpeg");
        assertThat(FileTypes.contentTypeFor("jpeg")).contains("image/jpeg");
        assertThat(FileTypes.contentTypeFor("png")).contains("image/png");
        assertThat(FileTypes.contentTypeFor("gif")).contains("image/gif");
        assertThat(FileTypes.contentTypeFor("webp")).contains("image/webp");
        assertThat(FileTypes.contentTypeFor("pdf")).contains("application/pdf");
        assertThat(FileTypes.contentTypeFor("doc")).contains("application/msword");
        assertThat(FileTypes.contentTypeFor("docx")).contains("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(FileTypes.contentTypeFor("xls")).contains("application/vnd.ms-excel");
        assertThat(FileTypes.contentTypeFor("xlsx")).contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(FileTypes.contentTypeFor("txt")).contains("text/plain; charset=utf-8");
    }

    @Test
    void otherTypesAreNotAllowed() {
        for (String extension : new String[] {"svg", "html", "htm", "js", "exe", "bat", "sh", "zip", "rar", "php", "jar", "xml", "mp4", "", "JPG"}) {
            assertThat(FileTypes.contentTypeFor(extension)).as(extension).isEmpty();
        }
    }

    @Test
    void theExtensionIsTheLowerCasePartAfterTheLastDot() {
        assertThat(FileTypes.extensionOf("Фото.JPG")).isEqualTo("jpg");
        assertThat(FileTypes.extensionOf("archive.tar.gz")).isEqualTo("gz");
        assertThat(FileTypes.extensionOf("noextension")).isEmpty();
        assertThat(FileTypes.extensionOf("dot.")).isEmpty();
        assertThat(FileTypes.extensionOf(".pdf")).isEqualTo("pdf");
    }

    @Test
    void realFilesAreRecognisedByTheirBeginning() {
        assertThat(FileTypes.looksLike("jpg", bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10))).isTrue();
        assertThat(FileTypes.looksLike("jpeg", bytes(0xFF, 0xD8, 0xFF, 0xDB))).isTrue();
        assertThat(FileTypes.looksLike("png", bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0))).isTrue();
        assertThat(FileTypes.looksLike("gif", ascii("GIF89a\u0001\u0000"))).isTrue();
        assertThat(FileTypes.looksLike("gif", ascii("GIF87a\u0001\u0000"))).isTrue();
        assertThat(FileTypes.looksLike("webp", ascii("RIFF\u0010\u0000\u0000\u0000WEBPVP8 "))).isTrue();
        assertThat(FileTypes.looksLike("pdf", ascii("%PDF-1.7\n"))).isTrue();
        assertThat(FileTypes.looksLike("docx", bytes('P', 'K', 3, 4, 0))).isTrue();
        assertThat(FileTypes.looksLike("xlsx", bytes('P', 'K', 3, 4, 0))).isTrue();
        assertThat(FileTypes.looksLike("doc", bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1, 0))).isTrue();
        assertThat(FileTypes.looksLike("xls", bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1))).isTrue();
        assertThat(FileTypes.looksLike("txt", "Просто текст\nв двух строках".getBytes(StandardCharsets.UTF_8))).isTrue();
    }

    @Test
    void aFileThatIsNotWhatItsExtensionSaysIsRefused() {
        byte[] html = ascii("<html><script>alert(1)</script></html>");
        byte[] exe = ascii("MZ\u0090\u0000");
        for (String extension : new String[] {"jpg", "jpeg", "png", "gif", "webp", "pdf", "docx", "xlsx", "doc", "xls"}) {
            assertThat(FileTypes.looksLike(extension, html)).as(extension + " with html").isFalse();
            assertThat(FileTypes.looksLike(extension, exe)).as(extension + " with exe").isFalse();
            assertThat(FileTypes.looksLike(extension, new byte[0])).as(extension + " empty").isFalse();
        }
        assertThat(FileTypes.looksLike("png", bytes(0xFF, 0xD8, 0xFF))).isFalse();
        assertThat(FileTypes.looksLike("pdf", ascii("%PD"))).isFalse();
        assertThat(FileTypes.looksLike("webp", ascii("RIFF\u0010\u0000\u0000\u0000WAVEfmt "))).isFalse();
        assertThat(FileTypes.looksLike("webp", ascii("RIFF"))).isFalse();
        assertThat(FileTypes.looksLike("docx", bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1))).isFalse();
    }

    @Test
    void aTextFileWithBinaryContentIsRefused() {
        assertThat(FileTypes.looksLike("txt", bytes('a', 'b', 0, 'c'))).isFalse();
        assertThat(FileTypes.looksLike("txt", bytes(0x4D, 0x5A, 0x90, 0x00))).isFalse();
    }

    @Test
    void anUnknownExtensionNeverLooksRight() {
        assertThat(FileTypes.looksLike("exe", ascii("MZ"))).isFalse();
        assertThat(FileTypes.looksLike("", ascii("anything"))).isFalse();
    }

    @Test
    void thePathAndTheControlCharactersAreDroppedFromTheName() {
        assertThat(FileTypes.cleanName("..\\..\\secret\\схема.pdf")).isEqualTo("схема.pdf");
        assertThat(FileTypes.cleanName("../../etc/passwd.txt")).isEqualTo("passwd.txt");
        assertThat(FileTypes.cleanName("  фото\t\n.jpg  ")).isEqualTo("фото.jpg");
        assertThat(FileTypes.cleanName("a\u0000b.pdf")).isEqualTo("ab.pdf");
        assertThat(FileTypes.cleanName("invoice‮fdp.exe")).isEqualTo("invoicefdp.exe");
    }

    @Test
    void aNameThatIsEmptyAfterTheCleaningIsNull() {
        assertThat(FileTypes.cleanName(null)).isNull();
        assertThat(FileTypes.cleanName("")).isNull();
        assertThat(FileTypes.cleanName("   ")).isNull();
        assertThat(FileTypes.cleanName("folder/")).isNull();
        assertThat(FileTypes.cleanName("\u0001\u0002")).isNull();
    }

    @Test
    void aLongNameIsCutBeforeItsExtensionWhichStays() {
        String name = "я".repeat(400) + ".pdf";

        String cleaned = FileTypes.cleanName(name);

        assertThat(cleaned.codePointCount(0, cleaned.length())).isEqualTo(255);
        assertThat(cleaned).endsWith(".pdf");
        assertThat(cleaned).startsWith("яяя");
    }

    @Test
    void aNameOfExactly255CharactersIsKept() {
        String name = "a".repeat(251) + ".pdf";
        assertThat(FileTypes.cleanName(name)).isEqualTo(name);
    }

    @Test
    void aLongNameWithoutAnExtensionIsCut() {
        String cleaned = FileTypes.cleanName("b".repeat(300));
        assertThat(cleaned).hasSize(255);
    }
}
