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
        assertThat(of("Interest Cr. for 03-Jan-2026", INCOME)).isEqualTo("Interest & Cashback");
        // No corpus statement prints these four; added because FlowClassifier already reads them as
        // interest income (Sid's decision, 2026-10-04).
        assertThat(of("INTEREST CREDITED 30-06-2026", INCOME)).isEqualTo("Interest & Cashback");
        assertThat(of("FD INTEREST 0000000000", INCOME)).isEqualTo("Interest & Cashback");
        assertThat(of("INT CREDIT JUN 2026", INCOME)).isEqualTo("Interest & Cashback");
        assertThat(of("INTEREST PAYMENT", INCOME)).isEqualTo("Interest & Cashback");
        assertThat(of("INTEREST PAYMENT", EXPENSE)).as("money leaving").isNull();
    }

    @Test
    void isInterestEarned_onlyForTheBanksInterestCredited() {
        assertThat(BankActivityCategory.isInterestEarned("Interest Cr. for 03-Jan-2026", INCOME)).isTrue();
        assertThat(BankActivityCategory.isInterestEarned("INTEREST PAID TILL 30-JUN-2026", INCOME)).isTrue();
        assertThat(BankActivityCategory.isInterestEarned("SAVING A/C CREDIT INTEREST", INCOME)).isTrue();

        assertThat(BankActivityCategory.isInterestEarned("Interest Cr. for 03-Jan-2026", EXPENSE))
                .as("money leaving").isFalse();
        assertThat(BankActivityCategory.isInterestEarned("Interest Cr. for 03-Jan-2026", null))
                .as("no direction given").isFalse();
        assertThat(BankActivityCategory.isInterestEarned("CASHBACK EARNED", INCOME)).as("cashback is not interest").isFalse();
        assertThat(BankActivityCategory.isInterestEarned("SAMPLE STORE 2ND OF 3 INSTALLMENTS INTEREST", INCOME)).isFalse();
        assertThat(BankActivityCategory.isInterestEarned("INTEREST ON EMI", INCOME)).isFalse();
        assertThat(BankActivityCategory.isInterestEarned(null, INCOME)).isFalse();
        assertThat(BankActivityCategory.isInterestEarned(" ", INCOME)).isFalse();
    }

    /** A card's instalment-plan line names interest without the bank paying any to you. */
    @Test
    void anInstalmentPlanCreditMentioningInterest_isNotInterestEarned() {
        assertThat(of("SAMPLE STORE 2ND OF 3 INSTALLMENTS INTEREST", INCOME)).isNull();
    }

    /** Interest charged and then refunded or reversed comes back; it was not earned. FlowClassifier
     *  reads these as a reversal or a refund ahead of interest, and so does this. */
    @Test
    void aRefundOrReversalOfInterest_isNotInterestEarned() {
        for (String description : new String[]{"INTEREST CR REVERSAL", "INTEREST REFUND CR", "INT CR REVERS",
                "INTEREST PAID - PAYMENT REVERSED"}) {
            assertThat(of(description, INCOME)).as(description).isNull();
            assertThat(BankActivityCategory.isInterestEarned(description, INCOME)).as(description).isFalse();
        }
        // "REVERSE SWEEP" is a deposit coming back, not a reversal: FlowClassifier's own reading.
        assertThat(BankActivityCategory.isInterestEarned("INT CR REVERSE SWEEP", INCOME)).isTrue();
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
        assertThat(of("UPI/000000000000/19:06:39/UPI/upsc.sbiepaylite@zz", EXPENSE)).isEqualTo("Taxes");
    }

    @Test
    void aMunicipalOrTransportOfficePayment_isTaxes() {
        assertThat(of("UPI/000000000000/MUNICIPAL CORPORATION PROPERTY TAX/sample@okbank", EXPENSE))
                .isEqualTo("Taxes");
        assertThat(of("UPI/000000000000/NAGAR NIGAM/sample@okbank", EXPENSE)).isEqualTo("Taxes");
        assertThat(of("RTO VEHICLE ROAD TAX", EXPENSE)).isEqualTo("Taxes");
    }

    @Test
    void aProvidentFundContribution_isInvestments_notTaxes() {
        // Money paid into the employees' provident fund is retirement savings that comes back to
        // the payer, not a tax or a fee.
        assertThat(of("NEFT-EPFO CONTRIBUTION-000000000000", EXPENSE)).isEqualTo("Investments");
        assertThat(of("UPI/000000000000/EPF VOLUNTARY CONTRIBUTION/sample@okbank", EXPENSE))
                .isEqualTo("Investments");
    }

    @Test
    void aProvidentFundWithdrawalCredited_hasNoBankActivityCategory() {
        assertThat(of("NEFT-EPFO SETTLEMENT-000000000000", INCOME)).isNull();
    }

    @Test
    void moneyFromAGovernmentBody_isNotATax() {
        assertThat(of("UPI/000000000000/19:06:39/UPI/upsc.sbiepaylite@zz", INCOME)).isNull();
    }

    // --- Savings and card bill payments ---

    @Test
    void aRecurringDepositInstalment_isInvestments() {
        assertThat(of("00000000000000- RD INSTALLMENT-MAY 2026", EXPENSE)).isEqualTo("Investments");
        assertThat(of("RECURRING DEPOSIT INSTALMENT", EXPENSE)).isEqualTo("Investments");
    }

    @Test
    void aPublicProvidentFundOrPensionInstalment_isInvestments_evenGluedToItsAccountNumber() {
        // The bank prints the scheme inside the account number or the scheme's own reference, so
        // the word stands alone nowhere in the narration.
        assertThat(of("MOB000000000/00000PPF000000000001", EXPENSE)).isEqualTo("Investments");
        assertThat(of("APY00000001_072026_000000000001_IN STALLMValue Dt 17/07/2026 SUMMARY", EXPENSE))
                .isEqualTo("Investments");
    }

    @Test
    void ppfOrApyInsideAnOrdinaryWord_isNothing() {
        assertThat(of("UPI/000000000000/SHOPPFAIR STORE/sample@okbank", EXPENSE)).isNull();
        assertThat(of("UPI/000000000000/HAPPY00000001 CAFE/sample@okbank", EXPENSE)).isNull();
        // A payee's UPI id that happens to start with the scheme's letters names a payee.
        assertThat(of("UPI/000000000000/apy123456@ybl/SAMPLE", EXPENSE)).isNull();
        assertThat(of("UPI/000000000000/sample.00000ppf000001@okbank/SAMPLE", EXPENSE)).isNull();
        // Money coming back from either scheme is not a contribution.
        assertThat(of("MOB000000000/00000PPF000000000001", INCOME)).isNull();
    }

    @Test
    void aCreditCardBillPaidFromThisAccount_isTransfer() {
        assertThat(of("Self BIL/INFT/AB00000001/CC BillPay-0001/Self", EXPENSE)).isEqualTo("Transfer");
        assertThat(of("UPI-PZ SAMPLE CC BILLPAY-pzsampleccbillpay.00000001@samplebank-XXXX0MERUPI-100000000001-REMARK", EXPENSE)) // synthetic-ok
                .isEqualTo("Transfer");
        // Net banking's bill payment names no card, but carries the card's masked number.
        assertThat(of("IB BILLPAY DR-SAMP92-400000XXXXXX0001", EXPENSE)).isEqualTo("Transfer"); // synthetic-ok
    }

    @Test
    void aCardBillPaidThroughTheCardBillApp_isTransfer() {
        // Paid to the app's own UPI id: that is how its card-bill payments arrive. A shop paid
        // through the same app is paid at the shop's own id, and is untouched.
        assertThat(of("UPI-CREDCLUB-CREDCLUB@ICICI-XXXX0000114-", EXPENSE)).isEqualTo("Transfer"); // synthetic-ok
        assertThat(of("UPI/DR/100000000001/CRED Clu/UTIB/cred.club@sampleb/", EXPENSE)).isEqualTo("Transfer");
        assertThat(of("UPI/DR/100000000001/SAMPLE CAFE/YESB/samplecafe@ybl/paid via cred", EXPENSE)).isNull();
    }

    @Test
    void anOrdinaryBillPaidOverNetBanking_isNotATransfer() {
        // The same net-banking bill payment to a utility: a consumer number, no masked card.
        assertThat(of("IB BILLPAY DR-SAMPLEPOWER-000000000001", EXPENSE)).isNull();
        // A bill paid with a debit card prints that card's own masked number: still a utility.
        assertThat(of("POS 400000XXXXXX0001 SAMPLE BILLPAY ELECTRICITY", EXPENSE)).isNull(); // synthetic-ok
    }

    @Test
    void aCardsOwnInstalment_isLoanEmi_andItsInterestStaysACharge() {
        assertThat(of("EMI PRINCIPAL - 1/6, REF# 00000001", EXPENSE)).isEqualTo("Loan EMI");
        assertThat(of("FP EMI 06/12(EXCL TAX   49.40)", EXPENSE)).isEqualTo("Loan EMI");
        assertThat(of("EMI INTEREST - 1/6, REF# 00000001", EXPENSE)).isEqualTo("Fees/Interest");
    }

    /** The third wording measured: the shop, the purchase date, then which instalment of how many. */
    @Test
    void aCardsOwnInstalment_inTheNthOfMWording_isLoanEmi_andItsInterestAFee() {
        assertThat(of("SAMPLE STORE 05 MAR 2ND OF 3 INSTALLMENTS PRINCIPAL", EXPENSE)).isEqualTo("Loan EMI");
        assertThat(of("SAMPLE STORE 05 MAR 2ND OF 3 INSTALLMENTS INTEREST", EXPENSE)).isEqualTo("Fees/Interest");
        // The same words as a credit stay unplaced: the statement prints the line back the same day,
        // and reconciliation already pairs it with the debit by their shared merchant.
        assertThat(of("SAMPLE STORE 05 MAR 2ND OF 3 INSTALLMENTS PRINCIPAL", INCOME)).isNull();
    }

    /** Not merchant vocabulary: a card's instalment line must not make the card a business. */
    @Test
    void aCardsOwnInstalmentLine_isNotAKnownMerchant() {
        assertThat(CounterpartyClassifier.classify("FP EMI 06/12(EXCL TAX   49.40)")).isNotEqualTo(CounterpartyType.BUSINESS);
        assertThat(CounterpartyClassifier.classify("EMI PRINCIPAL - 1/6, REF# 00000001")).isNotEqualTo(CounterpartyType.BUSINESS);
    }

    // --- isCardInstalment: the row a client marks as an instalment on an earlier purchase ---

    @Test
    void aCardsOwnInstalment_isMarked_inBothWordings() {
        assertThat(BankActivityCategory.isCardInstalment("EMI PRINCIPAL - 1/6, REF# 00000001", EXPENSE)).isTrue();
        assertThat(BankActivityCategory.isCardInstalment("FP EMI 06/12(EXCL TAX   49.40)", EXPENSE)).isTrue();
    }

    /** Interest and tax on an instalment are new cost, counted once: they are not a repeat of the purchase. */
    @Test
    void anInstalmentsInterestAndTax_areNotMarked() {
        assertThat(BankActivityCategory.isCardInstalment("EMI INTEREST - 1/6, REF# 00000001", EXPENSE)).isFalse();
        assertThat(BankActivityCategory.isCardInstalment("INTEREST ON EMI", EXPENSE)).isFalse();
        assertThat(BankActivityCategory.isCardInstalment("IGST DB @ 18.00%", EXPENSE)).isFalse();
    }

    /** Money in is never an instalment being billed, whatever it is worded as. */
    @Test
    void aCreditWordedAsAnInstalment_isNotMarked() {
        assertThat(BankActivityCategory.isCardInstalment("EMI PRINCIPAL - 1/6, REF# 00000001", INCOME)).isFalse();
        assertThat(BankActivityCategory.isCardInstalment("EMI PRINCIPAL - 1/6, REF# 00000001", null)).isFalse();
    }

    /** A loan instalment from a bank account, a recurring deposit, a shop named for the word: none
     *  is a card's instalment plan, and none repeats a purchase. */
    @Test
    void otherRowsThatMentionAnInstalment_areNotMarked() {
        assertThat(BankActivityCategory.isCardInstalment("ACH D- SAMPLE HOME LOAN EMI-0000000001", EXPENSE)).isFalse();
        assertThat(BankActivityCategory.isCardInstalment("00000000000001 RD INSTALLMENT SAMPLE 0001", EXPENSE)).isFalse();
        assertThat(BankActivityCategory.isCardInstalment("EMI SAMPLE STORE", EXPENSE)).isFalse();
        assertThat(BankActivityCategory.isCardInstalment("SAMPLE STORE 2ND OF 6 INSTALLMENTS PRINCIPAL", EXPENSE)).isFalse();
        assertThat(BankActivityCategory.isCardInstalment("", EXPENSE)).isFalse();
        assertThat(BankActivityCategory.isCardInstalment(null, EXPENSE)).isFalse();
    }

    /** A friend's own note on a payment is their words about a transfer, not the card's plan. */
    @Test
    void aPersonsPaymentWhoseNoteNamesAnInstalment_isNotMarked() {
        assertThat(BankActivityCategory.isCardInstalment(
                "UPI-AMIT KUMAR-amitkumar@okaxis-HDFC0000000-000000000000-EMI PRINCIPAL", EXPENSE)).isFalse();
    }

    /** The mark and the "Loan EMI" category read one word list: on each of these they agree. */
    @Test
    void theMark_agreesWithTheLoanEmiCategory() {
        for (String narration : new String[] {
                "EMI PRINCIPAL - 1/6, REF# 00000001", "FP EMI 06/12(EXCL TAX   49.40)",
                "EMI INTEREST - 1/6, REF# 00000001", "INTEREST ON EMI", "IGST DB @ 18.00%",
                "CC BILLPAY SAMPLE", "RD INSTALLMENT 0001", "SAMPLE STORE PURCHASE" }) {
            assertThat(BankActivityCategory.isCardInstalment(narration, EXPENSE))
                    .as(narration)
                    .isEqualTo("Loan EMI".equals(of(narration, EXPENSE)));
        }
    }

    // --- paysCardBill: the card-bill test on its own, shared with Insights ---

    @Test
    void paysCardBill_trueForCardBillAppAndSelfTransferWording() {
        assertThat(BankActivityCategory.paysCardBill("UPI-CREDCLUB-CREDCLUB@ICICI-XXXX0000114-")).isTrue(); // synthetic-ok
        assertThat(BankActivityCategory.paysCardBill("Self BIL/INFT/AB00000001/CC BillPay-0001/Self")).isTrue();
    }

    @Test
    void paysCardBill_netBankingNeedsAMaskedCard() {
        assertThat(BankActivityCategory.paysCardBill("IB BILLPAY DR-SAMP92-400000XXXXXX0001")).isTrue(); // synthetic-ok
        assertThat(BankActivityCategory.paysCardBill("IB BILLPAY DR-SAMPLEPOWER-000000000001")).isFalse();
    }

    @Test
    void paysCardBill_falseForNullBlankAndOrdinarySpend() {
        assertThat(BankActivityCategory.paysCardBill(null)).isFalse();
        assertThat(BankActivityCategory.paysCardBill("   ")).isFalse();
        assertThat(BankActivityCategory.paysCardBill("SAMPLE STORE PURCHASE")).isFalse();
    }

    @Test
    void paysCardBill_agreesWithOfOnEveryCardBillNarration() {
        for (String narration : new String[] {
                "Self BIL/INFT/AB00000001/CC BillPay-0001/Self",
                "UPI-CREDCLUB-CREDCLUB@ICICI-XXXX0000114-", // synthetic-ok
                "IB BILLPAY DR-SAMP92-400000XXXXXX0001", // synthetic-ok
                "IB BILLPAY DR-SAMPLEPOWER-000000000001",
                "POS 400000XXXXXX0001 SAMPLE BILLPAY ELECTRICITY", // synthetic-ok
                "SAMPLE STORE PURCHASE" }) {
            assertThat(BankActivityCategory.paysCardBill(narration))
                    .as(narration)
                    .isEqualTo("Transfer".equals(of(narration, EXPENSE)));
        }
    }

    @Test
    void aForeignCurrencyMarkupFee_isFeesInterest() {
        assertThat(of("CONSOLIDATED FCY MARKUP FEE (Ref# VT000000000000000000001)", EXPENSE)).isEqualTo("Fees/Interest");
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

    // --- A person is never the bank ---

    /** A payer's own note on a transfer from a friend is not the bank's activity. */
    @Test
    void aPersonsPaymentWhoseNoteNamesBankActivity_isLeftAlone() {
        assertThat(of("UPI-AMIT KUMAR-amitkumar@okaxis-HDFC0000000-000000000000-INTEREST PAID", INCOME)).isNull();
        assertThat(of("UPI-AMIT KUMAR-amitkumar@okaxis-HDFC0000000-000000000000-CASHBACK", INCOME)).isNull();
        assertThat(of("UPI-AMIT KUMAR-amitkumar@okaxis-HDFC0000000-000000000000-GST", EXPENSE)).isNull();
        assertThat(BankActivityCategory.isInterestEarned(
                "UPI-AMIT KUMAR-amitkumar@okaxis-HDFC0000000-000000000000-INTEREST PAID", INCOME)).isFalse();
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
