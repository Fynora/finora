package com.finora.transactions;

import com.finora.entity.Transaction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The short name of a hand-entered transaction. Every value is invented. */
class ManualEntryLabelTest {

    @Test
    void plainWordsKeepEveryWord_inLowerCase() {
        assertThat(TransactionService.manualEntryLabel("Transfer from ICICI", Transaction.Type.INCOME))
                .isEqualTo("transfer from icici");
        assertThat(TransactionService.manualEntryLabel("  Lunch   with team ", Transaction.Type.EXPENSE))
                .isEqualTo("lunch with team");
    }

    @Test
    void aPastedNarrationOrAReferenceStillGoesThroughTheNarrationRule() {
        assertThat(TransactionService.manualEntryLabel("UPI/DR/100000000001/SAMPLE STORE/HDFC/samplestore@okhdfc",
                Transaction.Type.EXPENSE)).isEqualTo("sample store");
        assertThat(TransactionService.manualEntryLabel("NEFT_IN:100000000001//SAMPLE PAYER", Transaction.Type.INCOME))
                .isEqualTo("sample payer");
    }

    @Test
    void interestKeepsItsOneLabel() {
        assertThat(TransactionService.manualEntryLabel("Interest credited", Transaction.Type.INCOME))
                .isEqualTo(com.finora.util.CategoryRules.INTEREST_LABEL);
    }

    @Test
    void aVeryLongTypedNameIsCutToTheColumn() {
        assertThat(TransactionService.manualEntryLabel("word ".repeat(80), Transaction.Type.EXPENSE)).hasSizeLessThanOrEqualTo(255);
    }
}
