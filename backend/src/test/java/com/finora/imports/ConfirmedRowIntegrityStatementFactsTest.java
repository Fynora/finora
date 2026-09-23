package com.finora.imports;

import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * International/foreign amount are facts the statement printed, so they come from the parse, not
 * from whatever the client echoes -- see {@link ConfirmedRowIntegrity#withStatementFacts}.
 */
class ConfirmedRowIntegrityStatementFactsTest {

    private static StagedRow staged(String description, String amount, boolean international,
                                    String currency, String foreignAmount) {
        return new StagedRow(LocalDate.of(2026, 9, 1), description, new BigDecimal(amount), "EXPENSE",
                "Other", "rule", null, false, null, null, null, RowKind.TRANSACTION, null, null, null, null)
                .withForeignSpend(international, currency, foreignAmount == null ? null : new BigDecimal(foreignAmount));
    }

    /** What a client that predates the new fields sends: none of them. */
    private static ConfirmedRow confirmedByAnOldClient(String description, String amount) {
        return new ConfirmedRow(LocalDate.of(2026, 9, 1), description, new BigDecimal(amount), "EXPENSE",
                "Other", true, "rule", null, false, null, null, false, null, null);
    }

    @Test
    void aClientThatSendsNoInternationalFieldsStillGetsTheStatementsOwn() {
        List<StagedRow> parsed = List.of(
                staged("SAMPLE CLOUD HOST", "1050.00", true, "USD", "12.50"),
                staged("IGST SAMPLE", "5.00", true, null, null),
                staged("SAMPLE AIRLINE", "5000.00", false, null, null));
        List<ConfirmedRow> confirmed = List.of(
                confirmedByAnOldClient("SAMPLE AIRLINE", "5000.00"),
                confirmedByAnOldClient("SAMPLE CLOUD HOST", "1050"),   // scale differs, same money
                confirmedByAnOldClient("IGST SAMPLE", "5.00"));

        List<ConfirmedRow> result = ConfirmedRowIntegrity.withStatementFacts(parsed, confirmed);

        assertThat(result).extracting(ConfirmedRow::description, ConfirmedRow::international,
                        ConfirmedRow::foreignCurrency)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("SAMPLE AIRLINE", false, null),
                        org.assertj.core.groups.Tuple.tuple("SAMPLE CLOUD HOST", true, "USD"),
                        org.assertj.core.groups.Tuple.tuple("IGST SAMPLE", true, null));
        assertThat(result.get(1).foreignAmount()).isEqualByComparingTo("12.50");
    }

    @Test
    void aClientClaimCannotMakeADomesticRowInternational() {
        List<StagedRow> parsed = List.of(staged("SAMPLE AIRLINE", "5000.00", false, null, null));
        ConfirmedRow claimed = confirmedByAnOldClient("SAMPLE AIRLINE", "5000.00")
                .withStatementFacts(true, "USD", new BigDecimal("99.00"));

        ConfirmedRow result = ConfirmedRowIntegrity.withStatementFacts(parsed, List.of(claimed)).get(0);

        assertThat(result.international()).isFalse();
        assertThat(result.foreignCurrency()).isNull();
        assertThat(result.foreignAmount()).isNull();
    }

    @Test
    void identicalRowsEachClaimTheirOwnParsedRowInDocumentOrder() {
        List<StagedRow> parsed = List.of(
                staged("SAMPLE FEE", "5.00", true, null, null),
                staged("SAMPLE FEE", "5.00", false, null, null));
        List<ConfirmedRow> confirmed = List.of(
                confirmedByAnOldClient("SAMPLE FEE", "5.00"), confirmedByAnOldClient("SAMPLE FEE", "5.00"));

        assertThat(ConfirmedRowIntegrity.withStatementFacts(parsed, confirmed))
                .extracting(ConfirmedRow::international).containsExactly(true, false);
    }

    @Test
    void aRowWithNoParsedCounterpartIsStoredDomesticNotAsClaimed() {
        ConfirmedRow unmatched = confirmedByAnOldClient("NOT IN THE STATEMENT", "1.00")
                .withStatementFacts(true, "EUR", BigDecimal.ONE);

        ConfirmedRow result = ConfirmedRowIntegrity.withStatementFacts(List.of(), List.of(unmatched)).get(0);

        assertThat(result.international()).isFalse();
        assertThat(result.foreignCurrency()).isNull();
    }
}
