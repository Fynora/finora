package com.finora.imports;

import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers StatementValidator.buildDetectedAccountInfo()'s opening/closing balance derivation --
 * had no dedicated test file before this. The same-day-cluster tests below lock in a real bug
 * found by hand-verifying against an actual PNB ONE statement (see StatementValidator's own doc
 * comments on chainFirst/chainLast for the full account): the statement's earliest date had 7
 * transactions, listed newest-first, and the old logic ("whichever one scanRow() saw first for
 * that date") picked the day's chronologically LAST transaction as if it were the first, silently
 * computing an opening balance off by six transactions' worth.
 */
class StatementValidatorTest {

    private final StatementValidator validator = new StatementValidator(com.finora.imports.product.ProductDiscovery.standard());

    private StagedRow row(String date, BigDecimal amount, String type) {
        return new StagedRow(LocalDate.parse(date), "txn", amount, type, "Other", "default", null, false, null, null);
    }

    private Map<String, String> balanceRow(String balance) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("Balance", balance);
        return row;
    }

    @Test
    void buildDetectedAccountInfo_withOneObservationPerDate_derivesOpeningAndClosingBalanceDirectly() {
        StatementValidator.AccountSignalAccumulator acc = new StatementValidator.AccountSignalAccumulator();
        // Opening 10000 -> -486 (debit) -> 9514 -> +2000 (credit) -> 11514, same as the existing
        // ImportServiceAskOnceTest fixture this mirrors -- distinct dates, no same-day cluster.
        StagedRow first = row("2026-07-10", new BigDecimal("486.00"), "EXPENSE");
        StagedRow second = row("2026-07-12", new BigDecimal("2000.00"), "INCOME");
        validator.scanRow(balanceRow("9514.00"), first, acc);
        validator.scanRow(balanceRow("11514.00"), second, acc);

        DetectedAccountInfo info = validator.buildDetectedAccountInfo("statement.csv", List.of(), -1, List.of(first, second), acc);

        assertThat(info.openingBalance()).isEqualByComparingTo("10000.00");
        assertThat(info.closingBalance()).isEqualByComparingTo("11514.00");
        // No payment-summary panel exists in a CSV export for a credit-card statement's total
        // amount due to come from -- CreditCardSummaryEvidence is a PDF-only concept.
        assertThat(info.totalAmountDue()).isNull();
    }

    /**
     * The actual bug: a 7-transaction cluster all on the statement's earliest date, file-ordered
     * newest-transaction-of-the-day-first (verified real PNB ONE behavior). The true opening
     * balance is 32013.97 (before the 7000 credit, which is chronologically first despite being
     * listed LAST) -- the old file-position logic would have computed 35942.97 instead (reversing
     * only the first-LISTED transaction, which is actually the day's last).
     */
    @Test
    void buildDetectedAccountInfo_reconstructsTrueOpeningBalance_fromANewestFirstSameDayCluster() {
        StatementValidator.AccountSignalAccumulator acc = new StatementValidator.AccountSignalAccumulator();
        String date = "2026-06-30";
        // (amount, type, balanceAfter) -- listed in the same top-to-bottom order the real
        // statement did: newest-transaction-of-the-day first.
        Object[][] rowsNewestFirst = {
                {"588.0", "EXPENSE", "35354.97"},
                {"1582.0", "EXPENSE", "35942.97"},
                {"440.0", "EXPENSE", "37524.97"},
                {"29.0", "EXPENSE", "37964.97"},
                {"800.0", "EXPENSE", "37993.97"},
                {"220.0", "EXPENSE", "38793.97"},
                {"7000.0", "INCOME", "39013.97"}, // chronologically FIRST despite being listed last
        };
        List<StagedRow> staged = new java.util.ArrayList<>();
        for (Object[] r : rowsNewestFirst) {
            StagedRow sr = row(date, new BigDecimal((String) r[0]), (String) r[1]);
            staged.add(sr);
            validator.scanRow(balanceRow((String) r[2]), sr, acc);
        }
        // A later (statement-latest-date) single observation so min/max date aren't the same day.
        StagedRow later = row("2026-07-01", new BigDecimal("100.0"), "EXPENSE");
        validator.scanRow(balanceRow("38913.97"), later, acc);
        staged.add(later);

        DetectedAccountInfo info = validator.buildDetectedAccountInfo("statement.csv", List.of(), -1, staged, acc);

        assertThat(info.openingBalance()).isEqualByComparingTo("32013.97");
    }

    /**
     * Mirror of the above for closingBalance, at the statement's LATEST date, with the cluster
     * file-ordered the opposite way (oldest-of-the-day first this time) -- proves the fix is
     * direction-agnostic rather than having just swapped which hardcoded direction it assumes.
     */
    @Test
    void buildDetectedAccountInfo_reconstructsTrueClosingBalance_fromAnOldestFirstSameDayCluster() {
        StatementValidator.AccountSignalAccumulator acc = new StatementValidator.AccountSignalAccumulator();
        StagedRow earlier = row("2026-06-29", new BigDecimal("50.0"), "EXPENSE");
        validator.scanRow(balanceRow("1000.00"), earlier, acc);

        String date = "2026-06-30";
        // Oldest-of-the-day listed first this time: 1000 -> +500 -> 1500 -> -200 -> 1300 (close).
        Object[][] rowsOldestFirst = {
                {"500.0", "INCOME", "1500.00"},
                {"200.0", "EXPENSE", "1300.00"},
        };
        List<StagedRow> staged = new java.util.ArrayList<>(List.of(earlier));
        for (Object[] r : rowsOldestFirst) {
            StagedRow sr = row(date, new BigDecimal((String) r[0]), (String) r[1]);
            staged.add(sr);
            validator.scanRow(balanceRow((String) r[2]), sr, acc);
        }

        DetectedAccountInfo info = validator.buildDetectedAccountInfo("statement.csv", List.of(), -1, staged, acc);

        assertThat(info.closingBalance()).isEqualByComparingTo("1300.00");
    }

    /**
     * Bug fix: TransactionNormalizer's balance-column fallback stages an explicit "Opening
     * Balance" label row (no debit/credit value, so it defaults to type=EXPENSE with
     * amount==balance) exactly like an ordinary transaction. The old logic unconditionally backed
     * out that row's own signedAmount from its balance -- for a label row that's
     * balance - (-balance), silently doubling the detected opening balance. Mirrors the equivalent
     * fixture already covered on the PDF path (PdfPreviewGeneratorTest's "OPENING BALANCE, 4 real
     * transactions, CLOSING BALANCE" golden fixture); this file had no CSV equivalent before now.
     */
    @Test
    void buildDetectedAccountInfo_doesNotDoubleTheOpeningBalance_whenTheFirstRowIsAnExplicitOpeningBalanceLabel() {
        StatementValidator.AccountSignalAccumulator acc = new StatementValidator.AccountSignalAccumulator();
        StagedRow openingRow = new StagedRow(LocalDate.parse("2026-07-01"), "Opening Balance",
                new BigDecimal("50000.00"), "EXPENSE", "Other", "default", null, false, null, null);
        validator.scanRow(balanceRow("50000.00"), openingRow, acc);
        StagedRow txn = row("2026-07-02", new BigDecimal("486.00"), "EXPENSE");
        validator.scanRow(balanceRow("49514.00"), txn, acc);

        DetectedAccountInfo info = validator.buildDetectedAccountInfo(
                "statement.csv", List.of(), -1, List.of(openingRow, txn), acc);

        assertThat(info.openingBalance()).isEqualByComparingTo("50000.00");
    }

    @Test
    void buildDetectedAccountInfo_withNoBalanceColumnAtAll_leavesBothBalancesNull() {
        StatementValidator.AccountSignalAccumulator acc = new StatementValidator.AccountSignalAccumulator();
        StagedRow onlyRow = row("2026-07-10", new BigDecimal("100.00"), "EXPENSE");
        validator.scanRow(new LinkedHashMap<>(), onlyRow, acc); // no "Balance" key at all

        DetectedAccountInfo info = validator.buildDetectedAccountInfo("statement.csv", List.of(), -1, List.of(onlyRow), acc);

        assertThat(info.openingBalance()).isNull();
        assertThat(info.closingBalance()).isNull();
    }

    // ---- the label,value rows a real export prints ABOVE its transaction table (Task 4) ----

    private static List<String[]> preambleThenTable() {
        return List.of(
                new String[]{""},
                new String[]{"Customer Details"},
                new String[]{"Account Title", "Sample Holder"},
                new String[]{"Communication Address", "12 Sample Street Sample Town  100001 India"},
                new String[]{"", "Sample Town Sample State"},
                new String[]{"Customer ID / CIF", "100000001"},
                new String[]{""},
                new String[]{"Customer Account Details"},
                new String[]{"Account Number", "20000000000001  "},
                new String[]{"Account Type", "Savings account"},
                new String[]{"Branch Details", "Sample Town, 1001, Sample Road, Sample District, INDIA, "},
                new String[]{"IFSC", "BDBL0XXXXXX"},
                new String[]{"MICR Code", "100000001"},
                new String[]{"Nomination Registered", "YES"},
                new String[]{"Statement Period", " "},
                new String[]{"\t\t\t\t\t\t\tTo"},
                new String[]{"Date", "Narration", "Withdrawal", "Deposit", "Balance"},
                new String[]{"01/07/2026", "SAMPLE PAYEE", "100.00", "", "900.00"});
    }

    @Test
    void preambleLabelValueRows_giveTheCsvPathItsHolderNumberIfscBranchAndAnIdentityKey() {
        StatementValidator.AccountSignalAccumulator acc = new StatementValidator.AccountSignalAccumulator();
        List<String[]> rows = preambleThenTable();
        int headerIdx = 16;

        validator.scanPreamble(rows, headerIdx, acc);
        DetectedAccountInfo info = validator.buildDetectedAccountInfo("1786000000000.csv", rows, headerIdx, List.of(), acc);

        assertThat(info.accountHolderName()).isEqualTo("Sample Holder");
        assertThat(info.accountNumberMasked()).as("masked the way every other path masks").endsWith("0001").doesNotContain("20000000000001");
        assertThat(info.ifscCode()).isEqualTo("BDBL0XXXXXX");
        assertThat(info.branchName()).as("the first comma-separated segment").isEqualTo("Sample Town");
        assertThat(info.bank().id()).isEqualTo("BANDHAN");
        assertThat(info.productIdentityHash()).as("a strong key from the full number, never stored").isNotNull().doesNotContain("20000000000001");
        assertThat(info.statementPeriodStart()).as("a blank period value contributes nothing").isNull();
    }

    @Test
    void aPreambleLabelWithABlankValue_contributesNothing_andRowsAfterTheHeaderAreNeverRead() {
        StatementValidator.AccountSignalAccumulator acc = new StatementValidator.AccountSignalAccumulator();
        List<String[]> rows = List.of(
                new String[]{"Account Holder", ""},
                new String[]{"Date", "Narration", "Withdrawal", "Deposit", "Balance"},
                new String[]{"Account Number", "20000000000001"});   // a table row, not a preamble row

        validator.scanPreamble(rows, 1, acc);

        assertThat(acc.accountHolderName).isNull();
        assertThat(acc.accountNumberMasked).isNull();
    }

    @Test
    void aCsvWithNoPreamble_isUnchanged() {
        StatementValidator.AccountSignalAccumulator acc = new StatementValidator.AccountSignalAccumulator();
        List<String[]> rows = List.of(
                new String[]{"Date", "Narration", "Withdrawal", "Deposit", "Balance"},
                new String[]{"01/07/2026", "SAMPLE PAYEE", "100.00", "", "900.00"});

        validator.scanPreamble(rows, 0, acc);
        DetectedAccountInfo info = validator.buildDetectedAccountInfo("statement.csv", rows, 0, List.of(), acc);

        assertThat(info.accountHolderName()).isNull();
        assertThat(info.productIdentityHash()).isNull();
    }
}
