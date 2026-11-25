package com.example.electronichome.statement;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

/**
 * Builds a statement of the charges in the layout of the real sheet (the same rows and columns of a block) with made-up
 * people, for the tests; the real file holds personal data and is not in the repository.
 */
public final class StatementFixture {

    /** One personal account of the statement: its number, the payer, the place and the two totals. */
    public record Account(String number, String payer, String place, BigDecimal utilities, BigDecimal capitalRepair, boolean withCapitalRepair) {
        public Account(String number, String payer, String place, String utilities, String capitalRepair) {
            this(number, payer, place, new BigDecimal(utilities), new BigDecimal(capitalRepair), true);
        }

        public Account withoutCapitalRepair() {
            return new Account(number, payer, place, utilities, capitalRepair, false);
        }
    }

    private StatementFixture() {
    }

    public static byte[] xls(String period, Account... accounts) {
        try (HSSFWorkbook workbook = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Лист_1");
            int row = 2;
            for (Account account : accounts) {
                row += block(sheet, row, period, account, false) + 2;
                if (account.withCapitalRepair()) {
                    row += block(sheet, row, period, account, true) + 2;
                }
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void text(Sheet sheet, int row, int column, String value) {
        Row r = sheet.getRow(row) != null ? sheet.getRow(row) : sheet.createRow(row);
        r.createCell(column).setCellValue(value);
    }

    private static void number(Sheet sheet, int row, int column, double value) {
        Row r = sheet.getRow(row) != null ? sheet.getRow(row) : sheet.createRow(row);
        r.createCell(column).setCellValue(value);
    }

    // writes a block from the row r0 on; returns the number of rows it takes
    private static int block(Sheet sheet, int r0, String period, Account account, boolean capital) {
        BigDecimal total = capital ? account.capitalRepair() : account.utilities();
        text(sheet, r0, 0, "Раздел 1");
        text(sheet, r0, 2, "Сведения о плательщике и исполнителе услуг");
        text(sheet, r0, 25, "Раздел 2");
        text(sheet, r0 + 1, 0, "за");
        text(sheet, r0 + 1, 1, period);
        text(sheet, r0 + 1, 6, "(расчетный период)");
        text(sheet, r0 + 2, 0, "Ф.И.О.(наименование) плательщика собственника/нанимателя");
        text(sheet, r0 + 3, 0, account.payer());
        text(sheet, r0 + 4, 0, "Адрес ");
        text(sheet, r0 + 4, 2, "РОССИЯ, 100000, Москва г, Тестовая ул, Дом № 1, " + account.place());
        text(sheet, r0 + 4, 25, "ТСЖ \"Тестовый дом\"");
        text(sheet, r0 + 4, 32, capital ? "р/с 40705810000000000002, в Тест-банке" : "р/с 40703810000000000001, в Тест-банке");
        text(sheet, r0 + 4, 42, "л/с №" + account.number());
        text(sheet, r0 + 4, 46, capital ? "Капитальный ремонт" : "Водоотведение, Холодное водоснабжение");
        number(sheet, r0 + 4, 52, total.doubleValue());
        text(sheet, r0 + 5, 6, "50,5 / 5 000,0");
        text(sheet, r0 + 5, 21, "2 / 2");
        text(sheet, r0 + 6, 0, "Наименование организации");
        text(sheet, r0 + 6, 7, "ТСЖ \"Тестовый дом\"");
        text(sheet, r0 + 6, 19, "ИНН");
        number(sheet, r0 + 6, 20, 7700000000.0);
        text(sheet, r0 + 7, 0, "Адрес");
        text(sheet, r0 + 7, 2, "100000, Москва г, Тестовая ул, дом № 1");
        text(sheet, r0 + 8, 0, "+70000000000");
        text(sheet, r0 + 12, 25, "Справочно:");
        text(sheet, r0 + 13, 25, "Задолженность за пред. периоды");
        number(sheet, r0 + 13, 32, 0.0);
        text(sheet, r0 + 14, 25, "Аванс на начало расч. периода");
        number(sheet, r0 + 14, 32, 100.5);
        text(sheet, r0 + 15, 25, "    в т.ч. оплаты");
        number(sheet, r0 + 15, 32, 100.5);
        text(sheet, r0 + 16, 25, "Дата посл. оплаты: 04.09.2026");
        text(sheet, r0 + 16, 50, "Итого к оплате:");
        number(sheet, r0 + 16, 51, total.doubleValue());
        text(sheet, r0 + 18, 0, "Раздел 3");
        text(sheet, r0 + 18, 2, "РАСЧЕТ РАЗМЕРА ПЛАТЫ ЗА СОДЕРЖАНИЕ И РЕМОНТ ЖИЛОГО ПОМЕЩЕНИЯ");
        text(sheet, r0 + 18, 41, "Раздел 4 ");
        text(sheet, r0 + 19, 0, "Виды услуг");
        for (int c : new int[] {0, 7, 9, 12, 15, 17, 19, 21, 24, 27, 30, 32, 34, 36, 38}) {
            text(sheet, r0 + 24, c, String.valueOf(c == 0 ? 1 : c == 7 ? 2 : c));
        }
        text(sheet, r0 + 24, 0, "1");
        text(sheet, r0 + 24, 7, "2");
        List<String> services = new ArrayList<>();
        if (capital) {
            services.add("Капитальный ремонт");
        } else {
            services.add("Водоотведение");
            services.add("Холодное водоснабжение");
        }
        int r = r0 + 25;
        BigDecimal rest = total;
        for (int i = 0; i < services.size(); i++) {
            BigDecimal part = i == services.size() - 1 ? rest : total.divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.DOWN);
            rest = rest.subtract(part);
            text(sheet, r, 0, services.get(i));
            text(sheet, r, 7, capital ? "м2" : "м3");
            number(sheet, r, 9, 41.0);
            text(sheet, r, 11, "2");
            number(sheet, r, 12, 0.0);
            number(sheet, r, 15, 52.48);
            number(sheet, r, 17, part.doubleValue());
            number(sheet, r, 21, part.doubleValue());
            number(sheet, r, 30, 0.0);
            number(sheet, r, 32, 0.0);
            number(sheet, r, 34, part.doubleValue());
            number(sheet, r, 43, 19.0);
            text(sheet, r, 45, "51/30");
            number(sheet, r, 49, 1363.5);
            r++;
        }
        text(sheet, r, 0, "Итого к оплате за расчетный период ");
        number(sheet, r, 21, total.doubleValue());
        number(sheet, r, 34, total.doubleValue());
        r++;
        text(sheet, r, 0, "* - указывается объем коммунальных услуг, определенный, исходя из: (1) - нормативов потребления");
        return r + 1 - r0;
    }
}
