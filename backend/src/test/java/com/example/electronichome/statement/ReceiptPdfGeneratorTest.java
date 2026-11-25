package com.example.electronichome.statement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.example.electronichome.statement.StatementFixture.Account;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;

class ReceiptPdfGeneratorTest {

    private final ReceiptPdfGenerator generator = new ReceiptPdfGenerator();

    private Statement.AccountStatement account(Account account) throws IOException {
        Statement statement = new StatementParser().parse(new ByteArrayInputStream(StatementFixture.xls("Сентябрь 2026 г.", account)));
        return statement.accounts().get(account.number());
    }

    @Test
    void makesAValidPdfWithAPagePerReceipt() throws IOException {
        byte[] pdf = generator.generate(account(new Account("7", "Иванов Иван Иванович", "Кв. 7", "8655.95", "3631.72")));

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        PdfReader reader = new PdfReader(pdf);
        assertThat(reader.getNumberOfPages()).isEqualTo(2);
    }

    @Test
    void printsTheSectionsTheTotalsAndTheCyrillicLettersReadably() throws IOException {
        byte[] pdf = generator.generate(account(new Account("7", "Ёжикова Юлия Щербаковна", "Кв. 7", "8655.95", "3631.72")));

        PdfReader reader = new PdfReader(pdf);
        String first = new PdfTextExtractor(reader).getTextFromPage(1).replaceAll("[\\u00a0\\s]+", " ");
        String second = new PdfTextExtractor(reader).getTextFromPage(2).replaceAll("[\\u00a0\\s]+", " ");
        assertThat(first).contains("Раздел 1", "Раздел 2", "Раздел 3", "Раздел 4", "Ёжикова Юлия Щербаковна", "Л/с №7", "8 655,95", "Водоотведение");
        // the capital repair has no section 4
        assertThat(second).contains("Раздел 1", "Раздел 2", "Раздел 3", "Капитальный ремонт", "3 631,72").doesNotContain("Раздел 4");
    }

    @Test
    void coversAllTheLettersOfTheAlphabetAndTheSigns() throws IOException {
        // the embedded font must have every letter that a name or an address can have
        String letters = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдеёжзийклмнопрстуфхцчшщъыьэюя№₽";
        byte[] pdf = generator.generate(account(new Account("7", letters, "Кв. 7", "1.00", "2.00")));

        // the long name wraps inside its cell: compare without the line breaks
        String text = new PdfTextExtractor(new PdfReader(pdf)).getTextFromPage(1).replaceAll("\\s+", "");
        assertThat(text).contains(letters);
    }

    @Test
    void worksWithOnlyOnePart() throws IOException {
        byte[] pdf = generator.generate(account(new Account("7", "Иванов Иван Иванович", "Кв. 7", "5.00", "6.00").withoutCapitalRepair()));
        assertThat(new PdfReader(pdf).getNumberOfPages()).isEqualTo(1);
    }
}
