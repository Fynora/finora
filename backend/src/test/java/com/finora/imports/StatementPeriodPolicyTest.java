package com.finora.imports;

import com.finora.accounts.AccountDto;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.imports.trust.HoldDecision;
import com.finora.imports.trust.TrustPredicate;
import com.finora.util.BankRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StatementPeriodPolicyTest {

    private static final LocalDate FY_START = LocalDate.of(2026, 4, 1);
    private static final LocalDate FY_END = LocalDate.of(2027, 3, 31);
    private static final LocalDate UPLOADED_ON = LocalDate.of(2026, 10, 6);

    private static DetectedAccountInfo detected(String bankId, LocalDate start, LocalDate end) {
        AccountDto.BankDto bank = bankId == null ? null : AccountDto.BankDto.from(BankRegistry.get(bankId));
        return new DetectedAccountInfo("Test Bank", "SAVINGS", new BigDecimal("100"), new BigDecimal("200"),
                start, end, null, null, null, null, null, null, null, bank,
                "SAVINGS", 0.85, false, List.of(), null,
                null, null, null, null, null, null, null);
    }

    @Test
    void aSliceStatementsPrintedPeriodIsNotJudged() {
        assertThat(StatementPeriodPolicy.judgedPeriod(detected("SLICE", FY_START, FY_END)))
                .containsExactly(null, null);
    }

    @Test
    void anyOtherBanksPrintedPeriodIsJudgedAsPrinted() {
        assertThat(StatementPeriodPolicy.judgedPeriod(detected("HDFC", FY_START, FY_END)))
                .containsExactly(FY_START, FY_END);
    }

    @Test
    void anUndetectedBankKeepsItsPrintedPeriod() {
        assertThat(StatementPeriodPolicy.judgedPeriod(detected(null, FY_START, FY_END)))
                .containsExactly(FY_START, FY_END);
    }

    @Test
    void nothingDetectedMeansNoPeriod() {
        assertThat(StatementPeriodPolicy.judgedPeriod(null)).containsExactly(null, null);
    }

    @Test
    void aHalfKnownPeriodOnAnotherBankIsCarriedAsItIs() {
        assertThat(StatementPeriodPolicy.judgedPeriod(detected("HDFC", FY_START, null)))
                .containsExactly(FY_START, null);
    }

    // The production case: a slice statement printing the whole financial year, uploaded before
    // that year ended, was held as "Statement period is in the future".

    @Test
    void theTrustCheckNoLongerHoldsASliceStatementWhosePrintedYearHasNotEnded() {
        HoldDecision decision = TrustPredicate.evaluate(List.of(),
                List.<LocalDate[]>of(StatementPeriodPolicy.judgedPeriod(detected("SLICE", FY_START, FY_END))),
                UPLOADED_ON);

        assertThat(decision.hold()).isFalse();
    }

    @Test
    void theTrustCheckStillHoldsTheSamePeriodOnAnotherBank() {
        HoldDecision decision = TrustPredicate.evaluate(List.of(),
                List.<LocalDate[]>of(StatementPeriodPolicy.judgedPeriod(detected("HDFC", FY_START, FY_END))),
                UPLOADED_ON);

        assertThat(decision.hold()).isTrue();
        assertThat(decision.reasons()).containsExactly("Statement period is in the future");
    }
}
