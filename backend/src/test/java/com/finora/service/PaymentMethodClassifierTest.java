package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.Transaction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentMethodClassifierTest {

    private static Transaction txn(String description) {
        Transaction t = new Transaction();
        t.setDescription(description);
        t.setSource(Transaction.Source.CSV_IMPORT);
        return t;
    }

    private static PaymentMethod bank(String description) {
        return PaymentMethodClassifier.classify(txn(description), Account.Type.SAVINGS);
    }

    @Test
    void readsTheRailAtTheFrontOfABankNarration() {
        assertThat(bank("UPI/DR/100000000001/SWIGGY/okaxis")).isEqualTo(PaymentMethod.UPI);
        assertThat(bank("NEFT-100000000001-ACME LTD")).isEqualTo(PaymentMethod.BANK_TRANSFER);
        assertThat(bank("IMPS-100000000001-ASHA-HDFC-xxxx")).isEqualTo(PaymentMethod.BANK_TRANSFER);
        assertThat(bank("MMT/IMPS/100000000001/RENT")).isEqualTo(PaymentMethod.BANK_TRANSFER);
        assertThat(bank("RTGS-100000000001-BUILDER")).isEqualTo(PaymentMethod.BANK_TRANSFER);
        assertThat(bank("POS 411111XXXXXX1111 BIGBASKET")).isEqualTo(PaymentMethod.CARD);
        assertThat(bank("ECOM PUR/AMAZON")).isEqualTo(PaymentMethod.CARD);
        assertThat(bank("ATM WDL 100000000001 MUMBAI")).isEqualTo(PaymentMethod.CASH);
        assertThat(bank("NWD-411111XXXXXX1111-100000000001")).isEqualTo(PaymentMethod.CASH);
        assertThat(bank("CHQ PAID 100001")).isEqualTo(PaymentMethod.CHEQUE);
        assertThat(bank("CLG/100000000001")).isEqualTo(PaymentMethod.CHEQUE);
        assertThat(bank("NACH-MUT-DR-100000000001")).isEqualTo(PaymentMethod.AUTO_DEBIT);
        assertThat(bank("ACH D- ACME INSURANCE")).isEqualTo(PaymentMethod.AUTO_DEBIT);
    }

    @Test
    void lookPastOneOrTwoGenericLeadingWords() {
        // SBI-style: the rail is not the first word.
        assertThat(bank("TO TRANSFER-UPI/DR/100000000001/SHOP")).isEqualTo(PaymentMethod.UPI);
        assertThat(bank("BY TRANSFER-NEFT*HDFC0XXXXXX*ACME")).isEqualTo(PaymentMethod.BANK_TRANSFER);
    }

    @Test
    void theEarliestRailWinsAndNothingAfterTheWindowIsRead() {
        // A payee that looks like a rail further along cannot change the answer.
        assertThat(bank("UPI/DR/100000000001/ATM CAFE/okaxis")).isEqualTo(PaymentMethod.UPI);
        // Past the fourth token it is not read at all.
        assertThat(bank("PAYMENT TO A SHOP VIA UPI")).isEqualTo(PaymentMethod.UNIDENTIFIED);
    }

    @Test
    void isCaseAndPunctuationInsensitive() {
        assertThat(bank("upi-dr-100000000001-swiggy")).isEqualTo(PaymentMethod.UPI);
        assertThat(bank("  Neft/100000000001/acme")).isEqualTo(PaymentMethod.BANK_TRANSFER);
    }

    @Test
    void doesNotMistakeAWordThatMerelyStartsLikeARail() {
        // "atmosphere", "positive", "cashew", "achievers" are payees, not rails.
        assertThat(bank("ATMOSPHERE CAFE")).isEqualTo(PaymentMethod.UNIDENTIFIED);
        assertThat(bank("POSITIVE ENERGY STORE")).isEqualTo(PaymentMethod.UNIDENTIFIED);
        assertThat(bank("CASHEW HOUSE")).isEqualTo(PaymentMethod.UNIDENTIFIED);
        assertThat(bank("ACHIEVERS ACADEMY FEES")).isEqualTo(PaymentMethod.UNIDENTIFIED);
    }

    @Test
    void aCreditCardBillPaymentIsNotACardPurchase() {
        assertThat(bank("CREDIT CARD PAYMENT 411111XXXXXX1111")).isEqualTo(PaymentMethod.UNIDENTIFIED);
    }

    @Test
    void leavesWordingThatNamesNoMethodUnidentifiedRatherThanGuessing() {
        assertThat(bank("SALARY MAY")).isEqualTo(PaymentMethod.UNIDENTIFIED);
        assertThat(bank("Transaction")).isEqualTo(PaymentMethod.UNIDENTIFIED);
        assertThat(bank("")).isEqualTo(PaymentMethod.UNIDENTIFIED);
        assertThat(bank("   ")).isEqualTo(PaymentMethod.UNIDENTIFIED);
        assertThat(bank(null)).isEqualTo(PaymentMethod.UNIDENTIFIED);
    }

    @Test
    void onlyABanksOwnNarrationIsReadForARail() {
        // A statement import and an Account Aggregator sync carry the bank's narration.
        Transaction aggregator = txn("UPI/DR/100000000001/SHOP");
        aggregator.setSource(Transaction.Source.ACCOUNT_AGGREGATOR);
        assertThat(PaymentMethodClassifier.classify(aggregator, Account.Type.SAVINGS)).isEqualTo(PaymentMethod.UPI);

        // Words the user typed, or an e-mail's subject, are free text: "ATM" near the front of
        // "ATM cafe lunch" is a coincidence, not a cash withdrawal.
        Transaction typed = txn("ATM cafe lunch");
        typed.setSource(Transaction.Source.MANUAL);
        assertThat(PaymentMethodClassifier.classify(typed, Account.Type.SAVINGS)).isEqualTo(PaymentMethod.UNIDENTIFIED);
        Transaction emailed = txn("UPI payment receipt from Shop");
        emailed.setSource(Transaction.Source.GMAIL_IMPORT);
        assertThat(PaymentMethodClassifier.classify(emailed, Account.Type.SAVINGS)).isEqualTo(PaymentMethod.UNIDENTIFIED);
    }

    @Test
    void aTypedInRowOnACreditCardAccountIsStillACardPayment() {
        Transaction typed = txn("Lunch");
        typed.setSource(Transaction.Source.MANUAL);
        assertThat(PaymentMethodClassifier.classify(typed, Account.Type.CREDIT_CARD)).isEqualTo(PaymentMethod.CARD);
    }

    @Test
    void theAccountTypeDecidesBeforeTheWording() {
        // Everything on a credit-card account is a card payment, even narration that says UPI or ATM.
        assertThat(PaymentMethodClassifier.classify(txn("UPI/DR/100000000001/SHOP"), Account.Type.CREDIT_CARD))
                .isEqualTo(PaymentMethod.CARD);
        assertThat(PaymentMethodClassifier.classify(txn(null), Account.Type.CREDIT_CARD)).isEqualTo(PaymentMethod.CARD);
        assertThat(PaymentMethodClassifier.classify(txn("UPI/DR/100000000001/SHOP"), Account.Type.WALLET))
                .isEqualTo(PaymentMethod.WALLET);
    }

    @Test
    void anUnknownAccountTypeIsReadLikeABankAccount() {
        assertThat(PaymentMethodClassifier.classify(txn("UPI/DR/100000000001/SHOP"), null)).isEqualTo(PaymentMethod.UPI);
        assertThat(PaymentMethodClassifier.classify(txn("UPI/DR/100000000001/SHOP"), Account.Type.INVESTMENT))
                .isEqualTo(PaymentMethod.UPI);
    }
}
