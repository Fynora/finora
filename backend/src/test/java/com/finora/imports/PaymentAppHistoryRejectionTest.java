package com.finora.imports;

import com.finora.dto.ImportDto.StagingResponse;
import com.finora.dto.ImportDto.UnparseableRow;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link ExtractionCheck} in isolation, for a payment app's own payment history -- audit F-08.
 *
 * <p>A Paytm "Passbook Payments History" lists payments made from several of the user's bank
 * accounts. It is not a statement of any one account, and every payment in it also appears in the
 * statement of the bank it was paid from. It used to reach the generic "could not find a
 * transaction table" rejection, which tells the user Finora failed on their bank statement when the
 * real answer is that this is the wrong kind of document.
 */
class PaymentAppHistoryRejectionTest {

    private static StagingResponse recovered(String... lines) {
        List<UnparseableRow> rows = Arrays.stream(lines)
                .map(l -> new UnparseableRow(Map.of("text", l), "No transaction table was recognized"))
                .toList();
        return new StagingResponse(List.of(), 0, 0, null, rows, null);
    }

    private static ApiException rejection(StagingResponse staged) {
        try {
            ExtractionCheck.rejectIfNothingWasExtracted(staged, new DocumentContext("PDF", "test"));
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("expected a rejection, and nothing was thrown");
    }

    @Test
    void aPaytmPaymentHistoryGetsItsOwnCode_notTheGenericNoTableFound() {
        ApiException e = rejection(recovered(
                "SAMPLE NAME",
                "Paytm Statement for 1 JAN'26 - 31 JAN'26",
                "Passbook Payments History",
                "All payments done by you on Paytm App are reflected in this statement"));

        assertThat(e.getCode()).isEqualTo(ErrorCode.IMPORT_PAYMENT_APP_HISTORY);
    }

    @Test
    void theHeadingsAreMatchedWhateverTheirCaseOrSpacing() {
        ApiException e = rejection(recovered(
                "PAYTM   STATEMENT  FOR 1 JAN'26",
                "passbook payments   history"));

        assertThat(e.getCode()).isEqualTo(ErrorCode.IMPORT_PAYMENT_APP_HISTORY);
    }

    @Test
    void oneHeadingAloneIsNotEnough() {
        // A bank statement could mention Paytm in a narration, or a page could carry a passbook
        // heading. Only both headings together identify the payment history.
        assertThat(rejection(recovered("Paytm Statement for January")).getCode())
                .isEqualTo(ErrorCode.IMPORT_NO_HEADER_DETECTED);
        assertThat(rejection(recovered("Passbook Payments History")).getCode())
                .isEqualTo(ErrorCode.IMPORT_NO_HEADER_DETECTED);
    }

    @Test
    void aDocumentThatStagedRowsIsNeverRejected() {
        // The check only runs once nothing was extracted, so a real statement that parses -- the
        // HDFC Paytm co-branded credit card, say -- can never be turned away by it.
        assertThatCode(() -> ExtractionCheck.rejectIfNothingWasExtracted(
                ExtractionCheckFixtures.withRows(), new DocumentContext("PDF", "test")))
                .doesNotThrowAnyException();
    }

    @Test
    void theMessageSaysWhatTheFileIsAndWhatToImportInstead() {
        String message = rejection(recovered(
                "Paytm Statement for 1 JAN'26 - 31 JAN'26", "Passbook Payments History")).getMessage();

        assertThat(message).containsIgnoringCase("payment app history");
        assertThat(message).containsIgnoringCase("bank statement");
        assertThat(message).doesNotContainIgnoringCase("could not find a transaction table");
    }

    @Test
    void theCodeIsFailFastAndAsksTheUserToAct() {
        assertThat(ErrorCode.IMPORT_PAYMENT_APP_HISTORY.code()).isEqualTo("IMPORT_018");
        assertThat(ErrorCode.IMPORT_PAYMENT_APP_HISTORY.retryPolicy()).isEqualTo(ErrorCode.RetryPolicy.FAIL_FAST);
        assertThat(ErrorCode.IMPORT_PAYMENT_APP_HISTORY.userActionRequired()).isTrue();
    }
}
