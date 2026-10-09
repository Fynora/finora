package com.finora.imports;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PaymentAppHistoryDetector#hasUpiAppRowGrammar} on the text runs of synthetic documents.
 *
 * <p>A UPI app's transaction history prints each payment as a line led by what happened to the money
 * ("Paid to", "Received from", ...) and a line naming the bank account it left or reached ("Paid by",
 * "Credited to", ...), with no balance column. Both halves are required, at the start of a run, and
 * a balance heading anywhere vetoes the match -- so a bank statement that happens to say "paid to"
 * inside a narration is never turned away.
 */
class UpiAppHistoryRowGrammarTest {

    /** The runs of {@code n} payments in the UPI grammar, in the order a real history prints them. */
    private static List<String> history(int n) {
        List<String> runs = new ArrayList<>(List.of("Transaction Statement for 9000000000",
                "Date", "Transaction Details", "Type", "Amount"));
        for (int i = 0; i < n; i++) {
            boolean credit = i % 2 == 1;
            runs.add("Jul 0" + (i + 1) + ", 2026");
            runs.add(credit ? "Received from SAMPLE PAYER" : "Paid to SAMPLE PAYEE");
            runs.add(credit ? "CREDIT" : "DEBIT");
            runs.add("₹100");
            runs.add("Transaction ID T0000000000000000000000");
            runs.add("UTR No. 000000000000");
            runs.add(credit ? "Credited to" : "Paid by");
            runs.add("XXXXXX0000");
        }
        return runs;
    }

    @Test
    void aUpiAppHistoryIsRecognised() {
        assertThat(PaymentAppHistoryDetector.hasUpiAppRowGrammar(history(10))).isTrue();
        assertThat(PaymentAppHistoryDetector.isPaymentAppHistory(history(10))).isTrue();
    }

    @Test
    void everyPaymentVerbOfTheGrammarCounts() {
        List<String> runs = new ArrayList<>();
        for (String verb : List.of("Paid to A", "Payment to B", "Transfer to C", "Received from D",
                "Cashback from E", "Refund from F", "Mobile recharged 9000000000")) {
            runs.add(verb);
            runs.add("Debited from XXXXXX0000");
        }
        assertThat(PaymentAppHistoryDetector.hasUpiAppRowGrammar(runs)).isTrue();

        // Each phrase counts on its own: three lines of any single one meet the threshold.
        for (String verb : List.of("Paid to A", "Payment to B", "Transfer to C", "Received from D",
                "Cashback from E", "Refund from F", "Mobile recharged 9000000000")) {
            List<String> only = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                only.add(verb);
                only.add("Paid by XXXXXX0000");
            }
            assertThat(PaymentAppHistoryDetector.hasUpiAppRowGrammar(only)).as(verb).isTrue();
        }
    }

    @Test
    void theThresholdIsExactlyThreeLinesOfEachHalf() {
        assertThat(PaymentAppHistoryDetector.MIN_UPI_ROWS).isEqualTo(3);
        assertThat(PaymentAppHistoryDetector.hasUpiAppRowGrammar(history(3))).isTrue();
        assertThat(PaymentAppHistoryDetector.hasUpiAppRowGrammar(history(2))).isFalse();
        assertThat(PaymentAppHistoryDetector.hasUpiAppRowGrammar(List.of())).isFalse();
    }

    @Test
    void bothHalvesAreRequired() {
        List<String> verbsOnly = history(10).stream().filter(t -> !t.startsWith("Paid by") && !t.startsWith("Credited to")).toList();
        List<String> fundingOnly = history(10).stream().filter(t -> !t.startsWith("Paid to") && !t.startsWith("Received from")).toList();
        assertThat(PaymentAppHistoryDetector.hasUpiAppRowGrammar(verbsOnly)).isFalse();
        assertThat(PaymentAppHistoryDetector.hasUpiAppRowGrammar(fundingOnly)).isFalse();
    }

    @Test
    void aBalanceHeadingAnywhereVetoesIt() {
        for (String heading : List.of("Balance", "Closing Balance", "Balance (INR)", "Avl Bal.", "OPENING BALANCE")) {
            List<String> runs = new ArrayList<>(history(10));
            runs.add(2, heading);
            assertThat(PaymentAppHistoryDetector.hasUpiAppRowGrammar(runs)).as(heading).isFalse();
        }
    }

    @Test
    void aBalanceFigureOrAPayeeNamedBalanceDoesNotVetoIt() {
        // Only a heading standing alone in its run is a balance column; a run that merely contains the
        // word (a payee, or a sentence with an amount after it) is not.
        List<String> runs = new ArrayList<>(history(10));
        runs.add("Paid to BALANCE FITNESS");
        runs.add("Balance 1,234.00 as on 31 Jul 2026");
        assertThat(PaymentAppHistoryDetector.hasUpiAppRowGrammar(runs)).isTrue();
    }

    @Test
    void theWordsInsideABankNarrationDoNotCount() {
        // A bank statement's narration can carry these phrases, but not at the start of a run.
        List<String> runs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            runs.add("UPI/000000000000/Paid to SAMPLE PAYEE/HDFC");
            runs.add("NEFT CR-SAMPLE-Received from SAMPLE PAYER");
            runs.add("Amount paid by cheque");
        }
        assertThat(PaymentAppHistoryDetector.hasUpiAppRowGrammar(runs)).isFalse();
    }

    @Test
    void anOrdinaryStatementsRunsAreNotAMatch() {
        List<String> runs = List.of("Account Statement", "Date", "Narration", "Withdrawal", "Deposit",
                "Closing Balance", "01/07/2026", "UPI-SAMPLE PAYEE-000000000000", "250.00", "24,750.00");
        assertThat(PaymentAppHistoryDetector.isPaymentAppHistory(runs)).isFalse();
    }

    @Test
    void oneLineOfEachHalfIsEnoughBesideTheAppsStatementAddress() {
        List<String> runs = new ArrayList<>(history(1));
        assertThat(PaymentAppHistoryDetector.hasUpiAppRowGrammar(runs)).isFalse();

        runs.add("This is a system generated statement. For any queries, contact us at https://support.phonepe.com/statement.");
        assertThat(PaymentAppHistoryDetector.hasUpiAppRowGrammar(runs)).isTrue();
    }

    @Test
    void theAddressAloneOrABareMentionOfTheAppIsNotEnough() {
        // Bank narrations name the app ("UPI-...-PHONEPE"), so the mention alone proves nothing, and
        // the address without the row grammar is not a payment history either.
        assertThat(PaymentAppHistoryDetector.hasUpiAppRowGrammar(
                List.of("https://support.phonepe.com/statement", "Date", "Amount"))).isFalse();

        List<String> mentionOnly = new ArrayList<>(history(2));
        mentionOnly.add("UPI-SAMPLE PAYEE-PHONEPE-000000000000");
        assertThat(PaymentAppHistoryDetector.hasUpiAppRowGrammar(mentionOnly)).isFalse();
    }

    @Test
    void aBalanceHeadingStillVetoesTheShortHistoryRule() {
        List<String> runs = new ArrayList<>(history(2));
        runs.add("https://support.phonepe.com/statement");
        runs.add("Closing Balance");
        assertThat(PaymentAppHistoryDetector.hasUpiAppRowGrammar(runs)).isFalse();
    }

    @Test
    void nullRunsAreSkipped() {
        List<String> runs = new ArrayList<>(history(5));
        runs.add(null);
        assertThat(PaymentAppHistoryDetector.hasUpiAppRowGrammar(runs)).isTrue();
    }
}
