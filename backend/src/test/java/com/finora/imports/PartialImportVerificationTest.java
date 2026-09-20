package com.finora.imports;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.VerificationFinding;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A file damaged part-way must never be rated CLEAN.
 *
 * <p>Found 2026-09-20 (owner asked "fix the partial-import verification gap"): with the bytes of a
 * statement overwritten mid-way, 1 of 6 rows imported, {@code reliabilityStatus} was CLEAN and
 * STATEMENT_TOTALS was VERIFIED. The totals check was circular -- the opening balance had been
 * derived from the one surviving row, so "opening + that row = closing" could not fail. Verification
 * cannot know what is missing from rows alone; the parser can, because it saw the content it had to
 * discard.
 */
class PartialImportVerificationTest {

    private static StagedAccountSection stage(byte[] pdf) throws Exception {
        return PdfGeneratorTestSupport.generator()
                .generateSectionsWithContext(UUID.randomUUID(), "statement.pdf", pdf).sections().get(0);
    }

    private static VerificationFinding integrity(StagedAccountSection s) {
        return s.verification().findings().stream()
                .filter(f -> "CONTENT_INTEGRITY".equals(f.rule())).findFirst().orElse(null);
    }

    @Test
    void aPartiallyDamagedStatementIsNotRatedClean() throws Exception {
        byte[] intact = PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample();
        StagedAccountSection damaged = stage(PdfGeneratorTestSupport.garbled(intact, 0.31));

        assertThat(damaged.rows().size()).as("the danger: rows are missing").isLessThan(stage(intact).rows().size());
        assertThat(integrity(damaged)).as("the parser's own evidence is reported").isNotNull();
        assertThat(integrity(damaged).outcome()).isEqualTo("FAILED");
        assertThat(damaged.verification().reliabilityStatus()).isEqualTo(ImportReliabilityStatus.NEEDS_ATTENTION);
    }

    /**
     * Wide check on the same fixture, modelling the real pipeline: an import that yields NO rows is
     * refused outright by {@code ExtractionCheck} (the user is told), so only imports that yield SOME rows
     * can be silent. Every damage offset that costs rows must be flagged; none may be rated CLEAN.
     */
    @Test
    void noDamageOffsetThatLosesRowsIsEverRatedClean() throws Exception {
        byte[] intact = PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample();
        int intactRows = stage(intact).rows().size();
        List<String> silent = new java.util.ArrayList<>();
        int partial = 0;
        for (int t = 0; t < 8; t++) {
            double fraction = 0.15 + 0.08 * t;
            try {
                var result = PdfGeneratorTestSupport.generator().generateSectionsWithContext(
                        UUID.randomUUID(), "statement.pdf", PdfGeneratorTestSupport.garbled(intact, fraction));
                StagedAccountSection s = result.sections().get(0);
                if (s.rows().isEmpty()) {
                    org.assertj.core.api.Assertions.assertThatThrownBy(
                                    () -> ExtractionCheck.rejectIfNothingWasExtracted(result.sections(), result.documentContext()))
                            .as("zero rows is refused, not imported: " + fraction)
                            .isInstanceOf(com.finora.exception.ApiException.class);
                } else if (s.rows().size() < intactRows) {
                    partial++;
                    if (s.verification().reliabilityStatus() == ImportReliabilityStatus.CLEAN) {
                        silent.add(fraction + " -> " + s.rows().size() + " of " + intactRows + " rows, CLEAN");
                    }
                }
            } catch (com.finora.exception.ApiException rejected) {
                // Refused outright (damaged / nothing readable): the user is told, so it is not silent.
            }
        }
        assertThat(partial).as("the fixture really does produce partial imports at some offsets").isGreaterThan(0);
        assertThat(silent).as("partial imports rated CLEAN").isEmpty();
    }

    /** Precision: an intact statement gets no integrity finding, so nothing changes for a healthy import. */
    @Test
    void anIntactStatementCarriesNoIntegrityFinding() throws Exception {
        StagedAccountSection intact = stage(PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample());

        assertThat(integrity(intact)).isNull();
    }
}
