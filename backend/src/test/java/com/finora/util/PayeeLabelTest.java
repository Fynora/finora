package com.finora.util;

import com.finora.entity.Transaction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The readable payee Quick sort and the money-in review show. Every narration here is invented. */
class PayeeLabelTest {

    private static String label(String description) {
        Transaction t = new Transaction();
        t.setDescription(description);
        return PayeeLabel.of(t, "Unknown");
    }

    @Test
    void theNameBeforeAUpiIdIsThePayee() {
        // "UPI/<name>/<id>@<bank>/..." -- a card statement's layout; the id is not a name.
        assertThat(label("UPI/SAMPLE CHAI CORNER/PAYTMQR1ABCDE@PAYTM/9000011111@P")).isEqualTo("SAMPLE CHAI CORNER"); // synthetic-ok
        assertThat(label("UPI/RAVI KUMAR/Q900011112@YBL/9000011111@P")).isEqualTo("RAVI KUMAR"); // synthetic-ok
        // After a leading copy of the name, as a savings layout prints it.
        assertThat(label("SAMPLE VEN UPI/SAMPLE VEN/sample.payu@a/UPIIntent/AXIS BANK/900011112203/X")) // synthetic-ok
                .isEqualTo("SAMPLE VEN");
        // The id can end the narration.
        assertThat(label("UPI/SAMPLE VENTURES PRIVATE L/SAMPLE.PAYU@AXISB")).isEqualTo("SAMPLE VENTURES PRIVATE L"); // synthetic-ok
    }

    @Test
    void theStandardSlashLayoutStillReadsItsNameSlot() {
        assertThat(label("UPI/DR/900011112201/SAMPLE ST/HDFC/samplestore/")).isEqualTo("SAMPLE ST"); // synthetic-ok
    }

    @Test
    void aNarrationWithNoNameIsShownAsItIs() {
        assertThat(label("UPI/RRN 900011112202/UPI")).isEqualTo("UPI/RRN 900011112202/UPI"); // synthetic-ok
    }

    @Test
    void noNarrationFallsBackToTheMerchantThenTheGivenWord() {
        Transaction t = new Transaction();
        assertThat(PayeeLabel.of(t, "Unknown")).isEqualTo("Unknown");
        t.setMerchant("Sample Merchant"); // synthetic-ok
        assertThat(PayeeLabel.of(t, "Unknown")).isEqualTo("Sample Merchant");
    }
}
