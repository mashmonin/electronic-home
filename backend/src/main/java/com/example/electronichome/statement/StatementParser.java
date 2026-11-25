package com.example.electronichome.statement;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Month;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.springframework.stereotype.Component;

import com.example.electronichome.statement.Statement.AccountStatement;
import com.example.electronichome.statement.Statement.Kind;
import com.example.electronichome.statement.Statement.Part;

/**
 * Reads the .xls statement of the charges (the first sheet). A receipt is a block that starts with "Раздел 1" in the
 * first column; the fields are found by their labels and by the columns of the printed form. Every personal account has
 * two blocks: the utilities and the capital repair.
 */
@Component
class StatementParser {

    /**
     * The columns of a row of the table of the services (section 3) in the sheet: name, unit, the volume of the
     * individual use and its sign (the way the volume was found), the same for the common needs, the tariff, charges, totals.
     */
    static final int[] SERVICE_COLUMNS = {0, 7, 9, 11, 12, 14, 15, 17, 19, 21, 24, 27, 30, 32, 34, 36, 38};
    /** The columns of the reference information (section 4). */
    static final int[] REFERENCE_COLUMNS = {41, 43, 45, 47, 49, 52};
    // shown as in the printed form: money with two digits, volumes with three, the tariff as it is with the thousands apart
    private static final Set<Integer> MONEY = Set.of(17, 19, 21, 24, 27, 30, 32, 34, 36, 38);
    private static final Set<Integer> VOLUME = Set.of(9, 12, 41, 43, 49, 52);
    private static final int TARIFF = 15;

    /** The cell of the row of a service (see {@link #serviceCells}) that comes from the column of the sheet. */
    static String at(String[] cells, int sheetColumn) {
        for (int i = 0; i < SERVICE_COLUMNS.length; i++) {
            if (SERVICE_COLUMNS[i] == sheetColumn) {
                return cells[i];
            }
        }
        for (int i = 0; i < REFERENCE_COLUMNS.length; i++) {
            if (REFERENCE_COLUMNS[i] == sheetColumn) {
                return cells[SERVICE_COLUMNS.length + i];
            }
        }
        throw new IllegalArgumentException("No such column: " + sheetColumn);
    }

    private static final Pattern PERIOD = Pattern.compile("(\\p{IsCyrillic}+)\\s+(\\d{4})\\s*г\\.?");
    private static final Pattern ACCOUNT = Pattern.compile("№\\s*(\\d+)");
    private static final String BLOCK_START = "Раздел 1";
    private static final String TOTAL_LABEL = "Итого к оплате за расчетный период";
    private static final int MAX_ACCOUNTS = 2000;

    private final DataFormatter formatter = new DataFormatter(new Locale("ru"));

    Statement parse(InputStream content) throws IOException {
        try (HSSFWorkbook workbook = new HSSFWorkbook(content)) {
            return parse(workbook.getSheetAt(0));
        } catch (StatementException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new StatementException("Не удалось прочитать файл: нужна ведомость начислений в формате .xls");
        }
    }

    private Statement parse(Sheet sheet) {
        List<Integer> starts = new ArrayList<>();
        for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
            if (text(sheet.getRow(r), 0).startsWith(BLOCK_START)) {
                starts.add(r);
            }
        }
        if (starts.isEmpty()) {
            throw new StatementException("В файле не найдены квитанции ведомости (нет разделов «Раздел 1»)");
        }
        YearMonth period = null;
        Map<String, Part[]> parts = new LinkedHashMap<>();
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int to = i + 1 < starts.size() ? starts.get(i + 1) : sheet.getLastRowNum() + 1;
            Block block = readBlock(sheet, from, to);
            if (period == null) {
                period = block.period();
            } else if (!period.equals(block.period())) {
                throw new StatementException("В ведомости разные расчётные периоды");
            }
            Part[] pair = parts.computeIfAbsent(block.number(), key -> new Part[2]);
            int slot = block.part().kind() == Kind.UTILITIES ? 0 : 1;
            if (pair[slot] != null) {
                throw new StatementException("В ведомости повторяется квитанция одного лицевого счёта");
            }
            pair[slot] = block.part();
            if (parts.size() > MAX_ACCOUNTS) {
                throw new StatementException("В ведомости слишком много лицевых счетов");
            }
        }
        Map<String, AccountStatement> accounts = new LinkedHashMap<>();
        parts.forEach((number, pair) -> accounts.put(number, new AccountStatement(number, pair[0], pair[1])));
        return new Statement(period, accounts);
    }

    private record Block(String number, YearMonth period, Part part) {
    }

    private Block readBlock(Sheet sheet, int from, int to) {
        String periodText = text(sheet.getRow(from + 1), 1);
        YearMonth period = period(periodText);

        int label = findStartsWith(sheet, from, to, 0, "Ф.И.О.");
        String payer = text(sheet.getRow(label + 1), 0);
        Row main = sheet.getRow(label + 2);
        Matcher account = ACCOUNT.matcher(text(main, 42));
        if (payer.isEmpty() || !account.find()) {
            throw new StatementException("В квитанции нет ФИО плательщика или номера лицевого счёта");
        }
        String servicesText = text(main, 46);
        Kind kind = servicesText.equalsIgnoreCase("Капитальный ремонт") ? Kind.CAPITAL_REPAIR : Kind.UTILITIES;

        Row details = sheet.getRow(label + 3);
        Row organization = sheet.getRow(label + 4);
        Row organizationAddress = sheet.getRow(label + 5);
        String phone = text(sheet.getRow(label + 6), 0);

        int totalRow = find(sheet, from, to, 50, "Итого к оплате:");
        BigDecimal total = number(sheet.getRow(totalRow), 51, "итог квитанции");

        int servicesTotal = findStartsWith(sheet, from, to, 0, TOTAL_LABEL);
        int numbering = findNumbering(sheet, from, servicesTotal);
        List<String[]> services = new ArrayList<>();
        for (int r = numbering + 1; r < servicesTotal; r++) {
            Row row = sheet.getRow(r);
            if (!text(row, 0).isEmpty()) {
                services.add(serviceCells(row));
            }
        }
        if (services.isEmpty()) {
            throw new StatementException("В квитанции нет строк с услугами");
        }
        int sectionThree = findStartsWith(sheet, from, to, 0, "Раздел 3");
        Part part = new Part(
                kind, periodText, payer, text(main, 2), text(main, 42), text(main, 25), text(main, 32), servicesText,
                text(details, 6), text(details, 21), text(organization, 7), text(organization, 20).replaceAll("\\.0$", ""),
                text(organizationAddress, 2), phone,
                labelled(sheet, from, to, "Задолженность"), labelled(sheet, from, to, "Аванс"),
                labelled(sheet, from, to, "в т.ч. оплаты"), optional(sheet, from, to, 25, "Дата посл. оплаты"),
                total, text(sheet.getRow(sectionThree), 2), services, serviceCells(sheet.getRow(servicesTotal)),
                optional(sheet, from, to, 0, "*"));
        return new Block(account.group(1), period, part);
    }

    private String[] serviceCells(Row row) {
        String[] cells = new String[SERVICE_COLUMNS.length + REFERENCE_COLUMNS.length];
        int i = 0;
        for (int column : SERVICE_COLUMNS) {
            cells[i++] = shown(row, column);
        }
        for (int column : REFERENCE_COLUMNS) {
            cells[i++] = shown(row, column);
        }
        return cells;
    }

    // The text of a cell as printed: money with two digits, other numbers without the trailing zeros.
    private String shown(Row row, int column) {
        Cell cell = row == null ? null : row.getCell(column);
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            BigDecimal value = BigDecimal.valueOf(cell.getNumericCellValue());
            if (MONEY.contains(column)) {
                return decimal(value, 2);
            }
            if (VOLUME.contains(column)) {
                return decimal(value, 3);
            }
            if (column == TARIFF) {
                return grouped(trimmed(value));
            }
            return trimmed(value).replace('.', ',');
        }
        return formatter.formatCellValue(cell).strip();
    }

    // 1312 -> "1312,00": a comma as the separator, no grouping
    private static String decimal(BigDecimal value, int digits) {
        return value.setScale(digits, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    // "1200" -> "1 200", "85.2" -> "85,2" (a non-breaking space between the thousands)
    private static String grouped(String plain) {
        String[] parts = plain.split("\\.");
        String whole = parts[0].replaceAll("\\B(?=(\\d{3})+(?!\\d))", "\u00a0");
        return parts.length > 1 ? whole + "," + parts[1] : whole;
    }

    private static String trimmed(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }

    // The amount on the line of the reference part of section 2 (debt, advance, payments); empty when the line is not there.
    private String labelled(Sheet sheet, int from, int to, String label) {
        for (int r = from; r < to; r++) {
            if (text(sheet.getRow(r), 25).startsWith(label)) {
                return number(sheet.getRow(r), 32, label).toPlainString();
            }
        }
        return "";
    }

    private String optional(Sheet sheet, int from, int to, int column, String prefix) {
        for (int r = from; r < to; r++) {
            if (text(sheet.getRow(r), column).startsWith(prefix)) {
                return text(sheet.getRow(r), column);
            }
        }
        return "";
    }

    private BigDecimal number(Row row, int column, String what) {
        Cell cell = row == null ? null : row.getCell(column);
        try {
            if (cell != null && cell.getCellType() == CellType.NUMERIC) {
                return BigDecimal.valueOf(cell.getNumericCellValue()).setScale(2, java.math.RoundingMode.HALF_UP);
            }
            return new BigDecimal(text(row, column).replace(',', '.')).setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new StatementException("Не удалось прочитать сумму: " + what);
        }
    }

    private static YearMonth period(String text) {
        Matcher matcher = PERIOD.matcher(text);
        if (matcher.find()) {
            for (Month month : Month.values()) {
                String name = month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, new Locale("ru"));
                if (name.equalsIgnoreCase(matcher.group(1))) {
                    return YearMonth.of(Integer.parseInt(matcher.group(2)), month);
                }
            }
        }
        throw new StatementException("Не удалось определить расчётный период (ожидается «за Сентябрь 2026 г.»)");
    }

    private int find(Sheet sheet, int from, int to, int column, String text) {
        for (int r = from; r < to; r++) {
            if (text(sheet.getRow(r), column).equals(text)) {
                return r;
            }
        }
        throw new StatementException("В квитанции нет строки «" + text + "»");
    }

    private int findStartsWith(Sheet sheet, int from, int to, int column, String prefix) {
        for (int r = from; r < to; r++) {
            if (text(sheet.getRow(r), column).startsWith(prefix)) {
                return r;
            }
        }
        throw new StatementException("В квитанции нет строки «" + prefix + "»");
    }

    // the row of the numbers of the columns (1, 2, 3, ...) that ends the head of the table of the services
    private int findNumbering(Sheet sheet, int from, int to) {
        for (int r = from; r < to; r++) {
            if (text(sheet.getRow(r), 0).equals("1") && text(sheet.getRow(r), 7).equals("2")) {
                return r;
            }
        }
        throw new StatementException("В квитанции нет таблицы услуг");
    }

    private String text(Row row, int column) {
        Cell cell = row == null ? null : row.getCell(column);
        return cell == null ? "" : formatter.formatCellValue(cell).strip();
    }
}
