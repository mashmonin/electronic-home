package com.example.electronichome.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class AttachmentTypesTest {

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
        assertThat(AttachmentTypes.contentTypeFor("jpg")).contains("image/jpeg");
        assertThat(AttachmentTypes.contentTypeFor("jpeg")).contains("image/jpeg");
        assertThat(AttachmentTypes.contentTypeFor("png")).contains("image/png");
        assertThat(AttachmentTypes.contentTypeFor("gif")).contains("image/gif");
        assertThat(AttachmentTypes.contentTypeFor("webp")).contains("image/webp");
        assertThat(AttachmentTypes.contentTypeFor("pdf")).contains("application/pdf");
        assertThat(AttachmentTypes.contentTypeFor("doc")).contains("application/msword");
        assertThat(AttachmentTypes.contentTypeFor("docx")).contains("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(AttachmentTypes.contentTypeFor("xls")).contains("application/vnd.ms-excel");
        assertThat(AttachmentTypes.contentTypeFor("xlsx")).contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(AttachmentTypes.contentTypeFor("txt")).contains("text/plain; charset=utf-8");
    }

    @Test
    void otherTypesAreNotAllowed() {
        for (String extension : new String[] {"svg", "html", "htm", "js", "exe", "bat", "sh", "zip", "rar", "php", "jar", "xml", "mp4", "", "JPG"}) {
            assertThat(AttachmentTypes.contentTypeFor(extension)).as(extension).isEmpty();
        }
    }

    @Test
    void theExtensionIsTheLowerCasePartAfterTheLastDot() {
        assertThat(AttachmentTypes.extensionOf("Фото.JPG")).isEqualTo("jpg");
        assertThat(AttachmentTypes.extensionOf("archive.tar.gz")).isEqualTo("gz");
        assertThat(AttachmentTypes.extensionOf("noextension")).isEmpty();
        assertThat(AttachmentTypes.extensionOf("dot.")).isEmpty();
        assertThat(AttachmentTypes.extensionOf(".pdf")).isEqualTo("pdf");
    }

    @Test
    void realFilesAreRecognisedByTheirBeginning() {
        assertThat(AttachmentTypes.looksLike("jpg", bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10))).isTrue();
        assertThat(AttachmentTypes.looksLike("jpeg", bytes(0xFF, 0xD8, 0xFF, 0xDB))).isTrue();
        assertThat(AttachmentTypes.looksLike("png", bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0))).isTrue();
        assertThat(AttachmentTypes.looksLike("gif", ascii("GIF89a\u0001\u0000"))).isTrue();
        assertThat(AttachmentTypes.looksLike("gif", ascii("GIF87a\u0001\u0000"))).isTrue();
        assertThat(AttachmentTypes.looksLike("webp", ascii("RIFF\u0010\u0000\u0000\u0000WEBPVP8 "))).isTrue();
        assertThat(AttachmentTypes.looksLike("pdf", ascii("%PDF-1.7\n"))).isTrue();
        assertThat(AttachmentTypes.looksLike("docx", bytes('P', 'K', 3, 4, 0))).isTrue();
        assertThat(AttachmentTypes.looksLike("xlsx", bytes('P', 'K', 3, 4, 0))).isTrue();
        assertThat(AttachmentTypes.looksLike("doc", bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1, 0))).isTrue();
        assertThat(AttachmentTypes.looksLike("xls", bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1))).isTrue();
        assertThat(AttachmentTypes.looksLike("txt", "Просто текст\nв двух строках".getBytes(StandardCharsets.UTF_8))).isTrue();
    }

    @Test
    void aFileThatIsNotWhatItsExtensionSaysIsRefused() {
        byte[] html = ascii("<html><script>alert(1)</script></html>");
        byte[] exe = ascii("MZ\u0090\u0000");
        for (String extension : new String[] {"jpg", "jpeg", "png", "gif", "webp", "pdf", "docx", "xlsx", "doc", "xls"}) {
            assertThat(AttachmentTypes.looksLike(extension, html)).as(extension + " with html").isFalse();
            assertThat(AttachmentTypes.looksLike(extension, exe)).as(extension + " with exe").isFalse();
            assertThat(AttachmentTypes.looksLike(extension, new byte[0])).as(extension + " empty").isFalse();
        }
        assertThat(AttachmentTypes.looksLike("png", bytes(0xFF, 0xD8, 0xFF))).isFalse();
        assertThat(AttachmentTypes.looksLike("pdf", ascii("%PD"))).isFalse();
        assertThat(AttachmentTypes.looksLike("webp", ascii("RIFF\u0010\u0000\u0000\u0000WAVEfmt "))).isFalse();
        assertThat(AttachmentTypes.looksLike("webp", ascii("RIFF"))).isFalse();
        assertThat(AttachmentTypes.looksLike("docx", bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1))).isFalse();
    }

    @Test
    void aTextFileWithBinaryContentIsRefused() {
        assertThat(AttachmentTypes.looksLike("txt", bytes('a', 'b', 0, 'c'))).isFalse();
        assertThat(AttachmentTypes.looksLike("txt", bytes(0x4D, 0x5A, 0x90, 0x00))).isFalse();
    }

    @Test
    void anUnknownExtensionNeverLooksRight() {
        assertThat(AttachmentTypes.looksLike("exe", ascii("MZ"))).isFalse();
        assertThat(AttachmentTypes.looksLike("", ascii("anything"))).isFalse();
    }

    @Test
    void thePathAndTheControlCharactersAreDroppedFromTheName() {
        assertThat(AttachmentTypes.cleanName("..\\..\\secret\\схема.pdf")).isEqualTo("схема.pdf");
        assertThat(AttachmentTypes.cleanName("../../etc/passwd.txt")).isEqualTo("passwd.txt");
        assertThat(AttachmentTypes.cleanName("  фото\t\n.jpg  ")).isEqualTo("фото.jpg");
        assertThat(AttachmentTypes.cleanName("a\u0000b.pdf")).isEqualTo("ab.pdf");
        assertThat(AttachmentTypes.cleanName("invoice‮fdp.exe")).isEqualTo("invoicefdp.exe");
    }

    @Test
    void aNameThatIsEmptyAfterTheCleaningIsNull() {
        assertThat(AttachmentTypes.cleanName(null)).isNull();
        assertThat(AttachmentTypes.cleanName("")).isNull();
        assertThat(AttachmentTypes.cleanName("   ")).isNull();
        assertThat(AttachmentTypes.cleanName("folder/")).isNull();
        assertThat(AttachmentTypes.cleanName("\u0001\u0002")).isNull();
    }

    @Test
    void aLongNameIsCutBeforeItsExtensionWhichStays() {
        String name = "я".repeat(400) + ".pdf";

        String cleaned = AttachmentTypes.cleanName(name);

        assertThat(cleaned.codePointCount(0, cleaned.length())).isEqualTo(255);
        assertThat(cleaned).endsWith(".pdf");
        assertThat(cleaned).startsWith("яяя");
    }

    @Test
    void aNameOfExactly255CharactersIsKept() {
        String name = "a".repeat(251) + ".pdf";
        assertThat(AttachmentTypes.cleanName(name)).isEqualTo(name);
    }

    @Test
    void aLongNameWithoutAnExtensionIsCut() {
        String cleaned = AttachmentTypes.cleanName("b".repeat(300));
        assertThat(cleaned).hasSize(255);
    }
}
