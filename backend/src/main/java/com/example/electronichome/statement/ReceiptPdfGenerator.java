package com.example.electronichome.statement;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.example.electronichome.statement.Statement.AccountStatement;
import com.example.electronichome.statement.Statement.Kind;
import com.example.electronichome.statement.Statement.Part;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

/**
 * Makes the PDF of a personal account from its two receipts of the statement, in the layout of the printed form of the
 * house: an A4 landscape page per receipt, with sections 1 and 2 side by side, section 3 (the services) and, for the
 * utilities, section 4 (the reference information) next to it. The fonts with the Cyrillic letters are embedded.
 */
@Component
class ReceiptPdfGenerator {

    private static final float THIN = 0.5f;
    private static final float THICK = 1.6f;
    // the widths of the form in points: section 1 | gap | section 2, and section 3 | gap | section 4
    private static final float[] TOP = {317f, 10f, 419f};
    private static final float[] SERVICES = {83f, 23f, 34.5f, 16f, 32f, 13.5f, 23f, 26f, 27f, 38f, 38f, 31.5f, 23.5f, 25f, 32.5f, 31f, 33f};
    private static final float[] REFERENCE = {31f, 30f, 31.6f, 28.6f, 42f, 41.3f};
    private static final float GAP = 10f;
    // the columns of the table of the services in the sheet, by the number of the column of the printed form
    private static final int[] REFERENCE_SHEET = StatementParser.REFERENCE_COLUMNS;

    private final byte[] regular = resource("fonts/DejaVuSansCondensed.ttf");
    private final byte[] bold = resource("fonts/DejaVuSansCondensed-Bold.ttf");

    byte[] generate(AccountStatement account) {
        List<Part> parts = new ArrayList<>();
        if (account.utilities() != null) {
            parts.add(account.utilities());
        }
        if (account.capitalRepair() != null) {
            parts.add(account.capitalRepair());
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("The account has no receipts");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(new Rectangle(PageSize.A4.getHeight(), PageSize.A4.getWidth()), 28, 68, 42, 24);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            document.addTitle("Квитанция, л/с " + account.number());
            document.open();
            BaseFont regularFont = BaseFont.createFont("DejaVuSansCondensed.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, regular, null);
            BaseFont boldFont = BaseFont.createFont("DejaVuSansCondensed-Bold.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, bold, null);
            Fonts fonts = new Fonts(regularFont, boldFont);
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) {
                    document.newPage();
                }
                write(document, writer, parts.get(i), fonts);
            }
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("The PDF of the receipt cannot be made", e);
        } finally {
            if (document.isOpen()) {
                document.close();
            }
        }
        return out.toByteArray();
    }

    private record Fonts(BaseFont regular, BaseFont bold) {
        Font r(float size) {
            return new Font(regular, size);
        }

        Font b(float size) {
            return new Font(bold, size);
        }
    }

    private void write(Document document, PdfWriter writer, Part part, Fonts fonts) throws DocumentException {
        // sections 1 and 2 stand side by side, each at its own height (a box of section 1 is not stretched to section 2)
        PdfPTable left = sectionOne(part, fonts);
        PdfPTable right = sectionTwo(part, fonts);
        for (PdfPTable table : new PdfPTable[] {left, right}) {
            table.setTotalWidth(table == left ? TOP[0] : TOP[2]);
            table.setLockedWidth(true);
        }
        float x = document.left();
        float y = document.top();
        left.writeSelectedRows(0, -1, x, y, writer.getDirectContent());
        right.writeSelectedRows(0, -1, x + TOP[0] + TOP[1], y, writer.getDirectContent());
        document.add(spacer(Math.max(left.getTotalHeight(), right.getTotalHeight()) + 14));
        PdfPTable bottom = servicesAndReference(part, fonts);
        document.add(bottom);
    }

    // ---- sections 1 and 2

    private PdfPTable sectionOne(Part part, Fonts fonts) throws DocumentException {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.setExtendLastRow(false);
        table.addCell(plain(title("Раздел 1", "Сведения о плательщике и исполнителе услуг", fonts)));

        PdfPTable box = new PdfPTable(1);
        box.setWidthPercentage(100);
        box.setExtendLastRow(false);
        Font r = fonts.r(6.5f);
        Font b = fonts.b(6.5f);
        box.addCell(boxCell(line(new float[] {14f, 80f, 223f}, fonts, cell("за", r), cell(part.periodText(), b), cell("(расчетный период)", r)), Rectangle.TOP));
        box.addCell(boxCell(line(new float[] {317f}, fonts, cell("Ф.И.О.(наименование) плательщика собственника/нанимателя", r)), Rectangle.NO_BORDER));
        box.addCell(boxCell(line(new float[] {317f}, fonts, cell(part.payer(), b)), Rectangle.NO_BORDER));
        box.addCell(boxCell(line(new float[] {30f, 287f}, fonts, cell("Адрес", r), cell(part.address(), b)), Rectangle.NO_BORDER));
        box.addCell(boxCell(line(new float[] {88f, 70f, 26f, 86f, 24f, 23f}, fonts,
                cell("Площадь помещ. / здания:", r), cell(part.area(), b), cell("кв.м.", r), cell("Кол-во прожив. / зарег.", r),
                cell(part.residents(), b), cell("чел.", r)), Rectangle.NO_BORDER));
        box.addCell(boxCell(line(new float[] {88f, 160f, 20f, 49f}, fonts,
                cell("Наименование организации", r), cell(part.organization(), b), cell("ИНН", r), cell(part.taxNumber(), b)), Rectangle.TOP));
        box.addCell(boxCell(line(new float[] {30f, 287f}, fonts, cell("Адрес", r), cell(part.organizationAddress(), b)), Rectangle.NO_BORDER));
        PdfPCell phone = boxCell(line(new float[] {317f}, fonts, cell(part.phone(), b)), Rectangle.BOTTOM);
        phone.setPaddingBottom(14f);
        box.addCell(phone);
        PdfPCell wrapper = new PdfPCell(box);
        wrapper.setBorder(Rectangle.NO_BORDER);
        wrapper.setPadding(0);
        table.addCell(wrapper);
        return table;
    }

    private PdfPTable sectionTwo(Part part, Fonts fonts) throws DocumentException {
        PdfPTable outer = new PdfPTable(1);
        outer.setWidthPercentage(100);
        outer.addCell(plain(title("Раздел 2", "Информация для внесения платы получателю платежа (получателям платежей)", fonts)));

        PdfPTable table = new PdfPTable(new float[] {83f, 148f, 60f, 89f, 39f});
        table.setWidthPercentage(100);
        Font head = fonts.b(6.2f);
        Font body = fonts.r(6.2f);
        for (String title : new String[] {"Наименование получателя\nплатежа", "Номер банковского счета\nи банковские реквизиты",
                "№ л/счета (иной\nидентификатор\nплательщика)", "Виды услуг", "Сумма\nк оплате, руб."}) {
            PdfPCell cell = bordered(title, head, Element.ALIGN_CENTER, THIN);
            cell.setPaddingBottom(5f);
            table.addCell(cell);
        }
        PdfPCell payee = bordered(part.payee(), body, Element.ALIGN_LEFT, THIN);
        payee.setVerticalAlignment(Element.ALIGN_MIDDLE);
        payee.setPadding(3f);
        payee.setPaddingTop(4f);
        payee.setPaddingBottom(4f);
        table.addCell(payee);
        for (PdfPCell cell : new PdfPCell[] {bordered(part.bankDetails(), body, Element.ALIGN_LEFT, THIN),
                bordered(capitalised(part.accountLabel()), body, Element.ALIGN_CENTER, THIN),
                bordered(part.servicesText(), body, Element.ALIGN_LEFT, THIN),
                bordered(grouped(part.total()), body, Element.ALIGN_RIGHT, THIN)}) {
            cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            cell.setPadding(3f);
            table.addCell(cell);
        }

        // the reference part of the section: one more bordered box under the table
        PdfPTable reference = new PdfPTable(new float[] {150f, 56f, 213f});
        reference.setWidthPercentage(100);
        reference.addCell(span(cell("Справочно:", fonts.b(6.5f)), 3));
        reference.addCell(referenceRow("Задолженность за пред. период", part.debt(), fonts));
        reference.addCell(referenceRow("Аванс на начало расч. периода", part.advance(), fonts));
        reference.addCell(referenceRow("    в т.ч. оплаты", part.payments(), fonts));
        PdfPCell last = span(cell(part.lastPayment(), fonts.r(6.2f)), 2);
        PdfPCell total = cell("Итого к оплате:   " + grouped(part.total()) + "  руб.", fonts.b(8f));
        total.setHorizontalAlignment(Element.ALIGN_RIGHT);
        reference.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        for (PdfPCell cell : new PdfPCell[] {last, total}) {
            cell.setBorder(Rectangle.NO_BORDER);
        }
        reference.addCell(last);
        reference.addCell(total);
        PdfPCell referenceBox = new PdfPCell(reference);
        referenceBox.setColspan(5);
        referenceBox.setBorder(Rectangle.LEFT | Rectangle.RIGHT | Rectangle.BOTTOM);
        referenceBox.setBorderWidth(THIN);
        referenceBox.setPadding(2f);
        table.addCell(referenceBox);

        outer.addCell(plain(table));
        return outer;
    }

    private PdfPCell referenceRow(String label, String amount, Fonts fonts) {
        PdfPCell labelCell = cell(label, fonts.r(6.2f));
        PdfPCell amountCell = cell(grouped(amount), fonts.b(6.2f));
        amountCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        PdfPCell fill = cell("", fonts.r(6.2f));
        fill.setBorder(Rectangle.NO_BORDER);
        // a row of three cells is added by the caller as one cell of the reference table: the label, the amount and an empty one
        PdfPTable row = new PdfPTable(new float[] {150f, 56f, 213f});
        row.setWidthPercentage(100);
        row.addCell(labelCell);
        row.addCell(amountCell);
        row.addCell(fill);
        PdfPCell wrapper = new PdfPCell(row);
        wrapper.setColspan(3);
        wrapper.setBorder(Rectangle.NO_BORDER);
        wrapper.setPadding(0);
        return wrapper;
    }

    // ---- sections 3 and 4

    private PdfPTable servicesAndReference(Part part, Fonts fonts) throws DocumentException {
        boolean withReference = part.kind() == Kind.UTILITIES;
        int columns = SERVICES.length + (withReference ? 1 + REFERENCE.length : 0);
        float[] widths = new float[columns];
        System.arraycopy(SERVICES, 0, widths, 0, SERVICES.length);
        if (withReference) {
            widths[SERVICES.length] = GAP;
            System.arraycopy(REFERENCE, 0, widths, SERVICES.length + 1, REFERENCE.length);
        }
        PdfPTable table = new PdfPTable(widths);
        table.setTotalWidth(sum(widths));
        table.setLockedWidth(true);
        table.setHorizontalAlignment(Element.ALIGN_LEFT);
        Font head = fonts.b(5.2f);
        Font body = fonts.r(5.8f);
        Font strong = fonts.b(5.4f);

        // the titles of the sections
        PdfPCell three = span(plain(title("Раздел 3", part.sectionThreeTitle(), fonts)), SERVICES.length);
        three.setBorder(Rectangle.NO_BORDER);
        table.addCell(three);
        if (withReference) {
            table.addCell(span(plainCell(), 1));
            PdfPCell four = span(plain(title("Раздел 4", "Справочная информация", fonts)), REFERENCE.length);
            four.setBorder(Rectangle.NO_BORDER);
            table.addCell(four);
        }

        // the head of the table of the services: three levels
        head(table, head, "Виды услуг", 1, 3);
        head(table, head, "Ед.изм.", 1, 3);
        head(table, head, "Объем коммуналь-\nных услуг*", 4, 2);
        head(table, head, "Тариф /\nРазм.\nпл.", 1, 3);
        head(table, head, "Размер платы за\nком. услуги, руб.", 2, 2);
        head(table, head, "Всего\nначисл. за\nрасчетный\nпериод,\nруб.", 1, 3);
        head(table, head, "Размер\nповы-\nшающе-\nго коэффи-\nциента", 1, 3);
        head(table, head, "Размер\nпревыше-\nния платы\nс учетом\nповыш.\nкоэффи-\nциента", 1, 3);
        head(table, head, "Пере-\nрасче-\nты\nвсего,\nруб.", 1, 3);
        head(table, head, "Льготы,\nсубси-\nдии,\nруб.", 1, 3);
        head(table, head, "Итого к оплате за\nрасч. период руб.", 3, 1);
        if (withReference) {
            table.addCell(plainCell());
            head(table, head, "Норматив\nпотребления\nком. услуг", 2, 2);
            head(table, head, "Тек. показания\nприборов учета\nком. услуг", 2, 2);
            head(table, head, "Суммарный объем\nком. услуг в доме", 2, 2);
        }
        // the second level: only the totals of the section are under their own title, the gaps stay empty
        head(table, head, "Всего", 1, 2);
        head(table, head, "в т.ч. за ком. усл.", 2, 1);
        if (withReference) {
            table.addCell(plainCell());
        }
        // the third level
        head(table, head, "индив.\nпотреб.", 2, 1, true);
        head(table, head, "общедом.\nнужды", 2, 1, true);
        head(table, head, "индив.\nпотреб.", 1, 1, true);
        head(table, head, "общедом.\nнужды", 1, 1, true);
        head(table, head, "инд.\nпотреб.", 1, 1, true);
        head(table, head, "общедом.\nнужды", 1, 1, true);
        if (withReference) {
            table.addCell(plainCell());
            head(table, head, "инд.\nпотреб.", 1, 1, true);
            head(table, head, "общедом.\nнужды", 1, 1, true);
            head(table, head, "инд.\n(квартир.)", 1, 1, true);
            head(table, head, "общедо-\nмовых", 1, 1, true);
            head(table, head, "в помещ. дома", 1, 1, true);
            head(table, head, "на общ.\nнужды", 1, 1, true);
        }

        // the numbers of the columns
        int[] spans = {1, 1, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};
        for (int i = 0; i < spans.length; i++) {
            table.addCell(numberCell(String.valueOf(i + 1), spans[i], fonts));
        }
        if (withReference) {
            table.addCell(plainCell());
            for (int i = 1; i <= REFERENCE.length; i++) {
                table.addCell(numberCell(String.valueOf(i), 1, fonts));
            }
        }

        // the services
        for (String[] service : part.services()) {
            for (int i = 0; i < StatementParser.SERVICE_COLUMNS.length; i++) {
                int align = i == 0 ? Element.ALIGN_LEFT : (i == 1 || i == 3 || i == 5 ? Element.ALIGN_CENTER : Element.ALIGN_RIGHT);
                table.addCell(bordered(service[i], body, align, THIN));
            }
            if (withReference) {
                table.addCell(plainCell());
                for (int i = 0; i < REFERENCE_SHEET.length; i++) {
                    table.addCell(bordered(StatementParser.at(service, REFERENCE_SHEET[i]), body, Element.ALIGN_RIGHT, THIN));
                }
            }
        }

        // the total row: the heavy frame around the columns up to the total of the section
        String[] totals = part.servicesTotal();
        PdfPCell label = bordered("Итого к оплате за расчетный период", strong, Element.ALIGN_LEFT, THICK);
        label.setColspan(7);
        label.setPaddingTop(2.2f);
        label.setPaddingBottom(2.2f);
        table.addCell(label);
        for (int sheetColumn : new int[] {17, 19, 21, 24, 27, 30, 32, 34}) {
            PdfPCell total = bordered(StatementParser.at(totals, sheetColumn), strong, Element.ALIGN_RIGHT, THICK);
            total.setPaddingTop(2.2f);
            total.setPaddingBottom(2.2f);
            table.addCell(total);
        }
        PdfPCell rest = span(plainCell(), 2);
        table.addCell(rest);
        if (withReference) {
            PdfPCell tail = span(plainCell(), 1 + REFERENCE.length);
            table.addCell(tail);
        }
        // the note under the tables
        if (!part.footnote().isBlank()) {
            PdfPCell note = span(new PdfPCell(new Phrase(part.footnote(), fonts.r(5.6f))), columns);
            note.setBorder(Rectangle.NO_BORDER);
            note.setPaddingLeft(0);
            note.setPaddingTop(5f);
            table.addCell(note);
        }
        return table;
    }

    private static void head(PdfPTable table, Font font, String text, int colspan, int rowspan) {
        head(table, font, text, colspan, rowspan, false);
    }

    private static void head(PdfPTable table, Font font, String text, int colspan, int rowspan, boolean small) {
        PdfPCell cell = bordered(text, font, Element.ALIGN_CENTER, THIN);
        cell.setColspan(colspan);
        cell.setRowspan(rowspan);
        cell.setVerticalAlignment(Element.ALIGN_TOP);
        table.addCell(cell);
    }

    private PdfPCell numberCell(String number, int colspan, Fonts fonts) {
        PdfPCell cell = bordered(number, fonts.b(5.8f), Element.ALIGN_CENTER, THIN);
        cell.setColspan(colspan);
        return cell;
    }

    // ---- small helpers

    private static Phrase title(String section, String title, Fonts fonts) {
        Phrase phrase = new Phrase();
        phrase.add(new Chunk(section + "  ", fonts.r(6.5f)));
        phrase.add(new Chunk(title, fonts.b(6.5f)));
        return phrase;
    }

    // a row of cells without borders, each of the given width
    private PdfPTable line(float[] widths, Fonts fonts, PdfPCell... cells) throws DocumentException {
        PdfPTable table = new PdfPTable(widths);
        table.setTotalWidth(sum(widths));
        table.setLockedWidth(true);
        for (PdfPCell cell : cells) {
            table.addCell(cell);
        }
        return table;
    }

    // a line of the box of section 1: the sides, and the top or the bottom where the box starts, is split or ends
    private static PdfPCell boxCell(PdfPTable line, int extra) {
        PdfPCell cell = new PdfPCell(line);
        cell.setBorder(Rectangle.LEFT | Rectangle.RIGHT | extra);
        cell.setBorderWidth(THIN);
        cell.setPadding(0);
        return cell;
    }

    private static PdfPCell cell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, font));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(1.6f);
        cell.setPaddingLeft(2.5f);
        return cell;
    }

    private static PdfPCell span(PdfPCell cell, int columns) {
        cell.setColspan(columns);
        return cell;
    }

    private static PdfPCell plain(Phrase phrase) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);
        cell.setPaddingBottom(2f);
        return cell;
    }

    private static PdfPCell plain(PdfPTable table) {
        PdfPCell cell = new PdfPCell(table);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);
        return cell;
    }

    private static PdfPCell plainCell() {
        PdfPCell cell = new PdfPCell(new Phrase(""));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);
        return cell;
    }

    private static PdfPCell bordered(String text, Font font, int alignment, float width) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, font));
        cell.setHorizontalAlignment(alignment);
        cell.setBorderWidth(width);
        cell.setPadding(1.2f);
        cell.setNoWrap(false);
        return cell;
    }

    private static Paragraph spacer(float height) {
        Paragraph paragraph = new Paragraph(" ");
        paragraph.setLeading(height);
        return paragraph;
    }

    private static String capitalised(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // "14741.33" -> "14 741,33" (a non-breaking space between the thousands, a comma)
    static String grouped(BigDecimal amount) {
        return grouped(amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    static String grouped(String plain) {
        if (plain == null || plain.isBlank()) {
            return "";
        }
        String[] parts = plain.split("\\.");
        String whole = parts[0].replaceAll("\\B(?=(\\d{3})+(?!\\d))", " ");
        return parts.length > 1 ? whole + "," + parts[1] : whole;
    }

    private static String grouped(Object total) {
        return grouped(total.toString());
    }

    private static float sum(float[] widths) {
        float total = 0;
        for (float width : widths) {
            total += width;
        }
        return total;
    }

    private static byte[] resource(String path) {
        try (InputStream in = ReceiptPdfGenerator.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("No font in the classpath: " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
