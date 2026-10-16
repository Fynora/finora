package com.finora.imports.refresh;

import com.finora.dto.ImportDto;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.imports.trust.HoldDecision;
import com.finora.imports.trust.TrustPredicate;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link StatementRefreshInputs#trustHold}: the upload path's hold, read off one refreshed section. */
class StatementRefreshTrustHoldTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    /** Positions 5 and 6 are the statement period; the rest is neutral, as in StagedForJobTest. */
    private static ImportDto.DetectedAccountInfo detected(LocalDate start, LocalDate end) {
        return new ImportDto.DetectedAccountInfo("HDFC Bank", "SAVINGS", null, null, start, end,
                null, null, null, null, null, null, null, null, "SAVINGS", 0.0, false,
                List.of(), null, null, null, null, null, null, null, null);
    }

    private static StagingResponse staging(ImportDto.DetectedAccountInfo detected, ImportDto.VerificationReport report) {
        return new StagingResponse(List.of(), 0, 0, detected, List.of(), report);
    }

    private static ImportDto.VerificationReport chain(String outcome) {
        return new ImportDto.VerificationReport(List.of(
                new ImportDto.VerificationFinding("BALANCE_CHAIN", outcome, Map.of())));
    }

    @Test
    void aSystematicBalanceBreak_holds() {
        HoldDecision decision = StatementRefreshInputs.trustHold(staging(null, chain("FAILED")), TODAY);

        assertThat(decision.hold()).isTrue();
        assertThat(StatementRefreshInputs.holdCategories(decision)).containsExactly("BALANCE_CHAIN_DISCREPANCY");
    }

    @Test
    void transactionsThatMissTheClosingBalance_hold() {
        // The scanned-statement case: rows read short of the statement's own closing balance.
        HoldDecision decision = StatementRefreshInputs.trustHold(staging(null, new ImportDto.VerificationReport(
                List.of(new ImportDto.VerificationFinding("STATEMENT_TOTALS", "FAILED",
                        Map.of("suspectedCause", "TRANSACTIONS"))))), TODAY);

        assertThat(StatementRefreshInputs.holdCategories(decision)).containsExactly("STATEMENT_TOTALS_DISAGREE");
    }

    @Test
    void aPeriodInTheFuture_holds_readFromTheSectionsOwnAccountDetails() {
        HoldDecision decision = StatementRefreshInputs.trustHold(
                staging(detected(LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 31)), null), TODAY);

        assertThat(decision.categories()).containsExactly(TrustPredicate.Category.PERIOD_INTEGRITY);
    }

    @Test
    void whatAnUploadReleases_isReleased() {
        assertThat(StatementRefreshInputs.trustHold(staging(null, null), TODAY).hold())
                .as("nothing verified, no period: a CSV").isFalse();
        assertThat(StatementRefreshInputs.trustHold(staging(
                detected(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)), chain("WARNING")), TODAY).hold())
                .as("one odd row is not a systematic break").isFalse();
    }
}
