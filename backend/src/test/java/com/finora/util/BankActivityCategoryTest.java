package com.finora.util;

import com.finora.entity.Transaction;
import org.junit.jupiter.api.Test;

import static com.finora.entity.Transaction.Type.EXPENSE;
import static com.finora.entity.Transaction.Type.INCOME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Narrations the bank itself writes -- what it paid you, what it charged you, a bill payment it
 * received on a card -- whose meaning the words and the direction settle together. Every
 * description below is synthetic, shaped after rows measured on the real statement corpus.
 */
class BankActivityCategoryTest {

    private static String of(String description, Transaction.Type direction) {
        return BankActivityCategory.of(description, direction).orElse(null);
    }

    // --- Money the bank or card pays you ---

    @Test
    void cashbackCredited_isInterestAndCashback() {
        assertThat(of("CASHBACK EARNED", INCOME)).isEqualTo(BankActivityCategory.INTEREST_AND_CASHBACK);
    }

    @Test
    void savingsInterestCredited_isInterestAndCashback_inEachPrintedSpelling() {
        assertThat(of("INTEREST PAID TILL 30-JUN-2026", INCOME)).isEqualTo("Interest & Cashback");
        assertThat(of("CREDIT INTEREST", INCOME)).isEqualTo("Interest & Cashback");
        assertThat(of("Int.Pd:01-05-2026 to 31-07-2026: 000000000000000", INCOME)).isEqualTo("Interest & Cashback");
        assertThat(of("SB INT CREDIT", INCOME)).isEqualTo("Interest & Cashback");
    }

    /** A card's instalment-plan line names interest without the bank paying any to you. */
    @Test
    void anInstalmentPlanCreditMentioningInterest_isNotInterestEarned() {
        assertThat(of("SAMPLE STORE 2ND OF 3 INSTALLMENTS INTEREST", INCOME)).isNull();
    }

    @Test
    void theSameWordsOnMoneyLeaving_areNotMoneyEarned() {
        assertThat(of("CASHBACK EARNED", EXPENSE)).isNull();
        assertThat(of("CREDIT INTEREST", EXPENSE)).isNull();
    }

    // --- Money the bank charges you ---

    @Test
    void smsAlertCharges_areFees_inEachPrintedSpelling() {
        assertThat(of("PC:SMS CHARGES+GST:JUN 2026", EXPENSE)).isEqualTo("Fees/Interest");
        assertThat(of("SMS CHRG FOR:01-04-2026to30-06-2026", EXPENSE)).isEqualTo("Fees/Interest");
        assertThat(of("CHRGS- SMS ALERT APR TO JUN 2026", EXPENSE)).isEqualTo("Fees/Interest");
        assertThat(of("Sms Charges For June Qtr ,2026", EXPENSE)).isEqualTo("Fees/Interest");
    }

    @Test
    void interestChargedOnAnEmi_isFees() {
        assertThat(of("INTEREST ON EMI", EXPENSE)).isEqualTo("Fees/Interest");
        assertThat(of("EMI INTEREST - 1/6, REF# 00000000", EXPENSE)).isEqualTo("Fees/Interest");
    }

    @Test
    void gstCharged_isTaxes() {
        assertThat(of("IGST-VPS0000000000000-RATE 18.0 -09 (Ref# VT000000000000000000000)", EXPENSE)).isEqualTo("Taxes");
        assertThat(of("GST", EXPENSE)).isEqualTo("Taxes");
    }

    // --- Payments to a government body ---

    @Test
    void aGovernmentFee_isTaxes() {
        assertThat(of("UPI-PASSPORT SEVA PROJEC-PASSPORTSEVA.GOI.SBIEPAYLITE@SBI-SBIN0000000-000000000000-MOPSUP", EXPENSE))
                .isEqualTo("Taxes");
        assertThat(of("UPI/000000000000/19:06:39/UPI/upsc.sbiepaylite@sb", EXPENSE)).isEqualTo("Taxes");
    }

    @Test
    void moneyFromAGovernmentBody_isNotATax() {
        assertThat(of("UPI/000000000000/19:06:39/UPI/upsc.sbiepaylite@sb", INCOME)).isNull();
    }

    // --- Savings and card bill payments ---

    @Test
    void aRecurringDepositInstalment_isInvestments() {
        assertThat(of("00000000000000- RD INSTALLMENT-MAY 2026", EXPENSE)).isEqualTo("Investments");
        assertThat(of("RECURRING DEPOSIT INSTALMENT", EXPENSE)).isEqualTo("Investments");
    }

    @Test
    void aBillPaymentReceivedOnACard_isTransfer() {
        assertThat(of("BBPS PAYMENT RECEIVED - DP000000000000SAMPLE", INCOME)).isEqualTo("Transfer");
        assertThat(of("BBPS PAYMENT", INCOME)).isEqualTo("Transfer");
    }

    /** A BBPS debit is a bill being PAID -- electricity, a phone -- not a card bill received. */
    @Test
    void aBillPaidOverBbps_isNotATransfer() {
        assertThat(of("BBPS SAMPLE ELECTRICITY BOARD", EXPENSE)).isNull();
    }

    // --- Everything else is left to the rest of the engine ---

    @Test
    void anOrdinaryPayment_hasNoBankActivityCategory() {
        assertThat(of("UPI-SAMPLE SHOP-Q000000000@YBL-YESB0XXXXXX-000000000000-UPI", EXPENSE)).isNull();
        assertThat(of("UPI PAYMENT RECEIVED/SAMPLEREFUND@AXISBANK", INCOME)).isNull();
    }

    @Test
    void blankOrMissingInput_hasNoBankActivityCategory() {
        assertThat(of(null, EXPENSE)).isNull();
        assertThat(of("   ", INCOME)).isNull();
        assertThat(of("CASHBACK EARNED", null)).isNull();
    }
}
