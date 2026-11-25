package com.example.electronichome.statement;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * A statement of the charges as read from the sheet: the period and, for every personal account, its two receipts (the
 * utilities and the capital repair). It holds personal data, so it lives only in memory while it is processed.
 */
record Statement(YearMonth period, Map<String, AccountStatement> accounts) {

    /** The kind of a receipt of the sheet. */
    enum Kind {
        UTILITIES,
        CAPITAL_REPAIR
    }

    /** The two receipts of a personal account; a part is null when the sheet has no such receipt for the account. */
    record AccountStatement(String number, Part utilities, Part capitalRepair) {
    }

    /**
     * One receipt (a block of the sheet). The service rows keep the cells as the text to print, in the order of
     * {@link StatementParser#SERVICE_COLUMNS}, followed by those of {@link StatementParser#REFERENCE_COLUMNS}.
     */
    record Part(
            Kind kind,
            String periodText,
            String payer,
            String address,
            String accountLabel,
            String payee,
            String bankDetails,
            String servicesText,
            String area,
            String residents,
            String organization,
            String taxNumber,
            String organizationAddress,
            String phone,
            String debt,
            String advance,
            String payments,
            String lastPayment,
            BigDecimal total,
            String sectionThreeTitle,
            List<String[]> services,
            String[] servicesTotal,
            String footnote) {
    }
}
