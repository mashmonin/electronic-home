package com.example.electronichome.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;

import com.example.electronichome.statement.Statement.AccountStatement;
import com.example.electronichome.statement.StatementFixture.Account;

class StatementParserTest {

    private final StatementParser parser = new StatementParser();

    private Statement parse(byte[] xls) throws IOException {
        return parser.parse(new ByteArrayInputStream(xls));
    }

    @Test
    void readsThePeriodAndTheTwoReceiptsOfEveryAccount() throws IOException {
        Statement statement = parse(StatementFixture.xls("Сентябрь 2026 г.",
                new Account("1", "Иванов Иван Иванович", "Кв. 1", "8655.95", "3631.72"),
                new Account("2", "Петрова Анна Петровна", "Кв. 2", "100.00", "20.50")));

        assertThat(statement.period()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(statement.accounts()).containsOnlyKeys("1", "2");
        AccountStatement first = statement.accounts().get("1");
        assertThat(first.utilities().total()).isEqualByComparingTo("8655.95");
        assertThat(first.capitalRepair().total()).isEqualByComparingTo("3631.72");
        assertThat(first.utilities().kind()).isEqualTo(Statement.Kind.UTILITIES);
        assertThat(first.capitalRepair().kind()).isEqualTo(Statement.Kind.CAPITAL_REPAIR);
        assertThat(first.utilities().payer()).isEqualTo("Иванов Иван Иванович");
        assertThat(first.utilities().address()).endsWith("Кв. 1");
        assertThat(first.utilities().taxNumber()).isEqualTo("7700000000");
    }

    @Test
    void readsTheServicesWithTheirAmounts() throws IOException {
        Statement statement = parse(StatementFixture.xls("Сентябрь 2026 г.", new Account("1", "Иванов Иван Иванович", "Кв. 1", "100.00", "20.50")));

        Statement.Part utilities = statement.accounts().get("1").utilities();
        assertThat(utilities.services()).hasSize(2);
        assertThat(utilities.services().get(0)[0]).isEqualTo("Водоотведение");
        // as in the printed form: money with two digits and a comma, volumes with three, the sign of the way in a cell of its own
        String[] first = utilities.services().get(0);
        assertThat(StatementParser.at(first, 9)).isEqualTo("41,000");
        assertThat(StatementParser.at(first, 11)).isEqualTo("2");
        assertThat(StatementParser.at(first, 15)).isEqualTo("52,48");
        assertThat(StatementParser.at(first, 21)).isEqualTo("50,00");
        assertThat(StatementParser.at(first, 43)).isEqualTo("19,000");
        assertThat(StatementParser.at(utilities.servicesTotal(), 21)).isEqualTo("100,00");
        assertThat(utilities.debt()).isEqualTo("0.00");
        assertThat(utilities.advance()).isEqualTo("100.50");
        assertThat(utilities.lastPayment()).isEqualTo("Дата посл. оплаты: 04.09.2026");
    }

    @Test
    void keepsAnOfficeAndAMissingPartForTheProcessingToDecide() throws IOException {
        Statement statement = parse(StatementFixture.xls("Сентябрь 2026 г.",
                new Account("00", "Сидоров Сидор Сидорович", "Оф. 1", "1.00", "2.00"),
                new Account("5", "Кузнецов Кузьма Кузьмич", "Кв. 5", "1.00", "2.00").withoutCapitalRepair()));

        assertThat(statement.accounts().get("00").utilities().address()).endsWith("Оф. 1");
        assertThat(statement.accounts().get("5").capitalRepair()).isNull();
    }

    @Test
    void rejectsAFileThatIsNotAStatement() {
        assertThatThrownBy(() -> parse("%PDF-1.4 not a sheet".getBytes()))
                .isInstanceOf(StatementException.class).hasMessageContaining(".xls");
    }

    @Test
    void rejectsASheetWithoutReceipts() {
        assertThatThrownBy(() -> parse(StatementFixture.xls("Сентябрь 2026 г.")))
                .isInstanceOf(StatementException.class).hasMessageContaining("Раздел 1");
    }

    @Test
    void rejectsAnUnreadablePeriod() {
        assertThatThrownBy(() -> parse(StatementFixture.xls("Сентябрь", new Account("1", "Иванов Иван Иванович", "Кв. 1", "1.00", "2.00"))))
                .isInstanceOf(StatementException.class).hasMessageContaining("период");
    }

    @Test
    void rejectsDifferentPeriodsInOneFile() throws IOException {
        byte[] sept = StatementFixture.xls("Сентябрь 2026 г.", new Account("1", "Иванов Иван Иванович", "Кв. 1", "1.00", "2.00"));
        assertThat(parse(sept).period()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(parse(StatementFixture.xls("Октябрь 2026 г.", new Account("1", "Иванов Иван Иванович", "Кв. 1", "1.00", "2.00"))).period())
                .isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    void amountsAreExactDecimals() throws IOException {
        Statement statement = parse(StatementFixture.xls("Сентябрь 2026 г.", new Account("1", "Иванов Иван Иванович", "Кв. 1", "19416.71", "0.10")));
        assertThat(statement.accounts().get("1").utilities().total()).isEqualTo(new BigDecimal("19416.71"));
        assertThat(statement.accounts().get("1").capitalRepair().total()).isEqualTo(new BigDecimal("0.10"));
    }
}
