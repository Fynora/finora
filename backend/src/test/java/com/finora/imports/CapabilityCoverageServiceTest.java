package com.finora.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.dto.ImportDto.UnparseableRow;
import com.finora.repository.StatementImportRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityCoverageServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CapabilityCoverageService service =
            new CapabilityCoverageService(mock(StatementImportRepository.class), objectMapper);

    private StatementImportRepository.CapabilityData importWith(String capabilitiesJson, String unparseableJson) {
        StatementImportRepository.CapabilityData d = mock(StatementImportRepository.CapabilityData.class);
        when(d.getActivatedCapabilitiesJson()).thenReturn(capabilitiesJson);
        when(d.getUnparseableSummaryJson()).thenReturn(unparseableJson);
        return d;
    }

    private String capabilities(String... names) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"capability\":\"").append(names[i]).append("\",\"status\":\"SUCCESS\"}");
        }
        return sb.append(']').toString();
    }

    @Test
    void countsEachCapabilityOncePerImportNotOncePerActivation() {
        // A capability firing forty times in one document is one document's worth of evidence that
        // it works, not forty. Counting raw activations would make a per-row capability look
        // enormously better covered than one that runs once per file.
        var map = service.aggregate(List.of(
                importWith(capabilities("WRAPPED_DESCRIPTION", "WRAPPED_DESCRIPTION", "REPEATED_HEADER"), null)));

        assertThat(map.activations()).containsEntry("WRAPPED_DESCRIPTION", 1);
        assertThat(map.activations()).containsEntry("REPEATED_HEADER", 1);
    }

    @Test
    void reportsCapabilitiesThatHaveNeverFiredAtAll() {
        // The single most useful output: a registry capability with no activations is either dead
        // code or a hole in the corpus. Deriving the capability list from observed activations
        // instead would make this impossible to report -- an unfired capability would just be
        // absent.
        var map = service.aggregate(List.of(importWith(capabilities("WRAPPED_DESCRIPTION"), null)));

        assertThat(map.neverActivated()).contains("OFFSET_COLUMN_ANCHORS", "COMPOSITE_STATEMENT");
        assertThat(map.neverActivated()).doesNotContain("WRAPPED_DESCRIPTION");
    }

    @Test
    void coverageRatioReflectsHowMuchOfTheRegistryRealDocumentsExercise() {
        var none = service.aggregate(List.of());
        assertThat(none.coverageRatio()).isZero();

        var some = service.aggregate(List.of(importWith(
                capabilities(CapabilityCoverageService.KNOWN_CAPABILITIES.toArray(String[]::new)), null)));
        assertThat(some.coverageRatio()).isEqualTo(1.0);
    }

    @Test
    void reportsHowManyImportsTheNumbersCameFrom() {
        // A coverage figure from three documents means something very different from one drawn from
        // three hundred; omitting the denominator invites reading the first as the second.
        var map = service.aggregate(List.of(
                importWith(capabilities("WRAPPED_DESCRIPTION"), null),
                importWith(capabilities("REPEATED_HEADER"), null)));

        assertThat(map.importsAnalysed()).isEqualTo(2);
    }

    @Test
    void aggregatesUnparseableRowsIntoAPrioritisedBacklog() throws Exception {
        String heavy = objectMapper.writeValueAsString(UnparseableRowSummary.of(List.of(
                new UnparseableRow(Map.of("date", "x", "narration", "y"), UnparseableReasons.NO_DATE_COLUMN),
                new UnparseableRow(Map.of("date", "x", "narration", "y"), UnparseableReasons.NO_DATE_COLUMN),
                new UnparseableRow(Map.of("date", "x", "narration", "y"), UnparseableReasons.NO_AMOUNT_COLUMN))));

        var map = service.aggregate(List.of(importWith(capabilities("WRAPPED_DESCRIPTION"), heavy)));

        assertThat(map.rowsLost()).isEqualTo(3);
        // Highest first: a backlog is only useful in priority order, which is the whole reason for
        // counting instead of prioritising by whichever document was looked at most recently.
        assertThat(map.unparseableReasons().keySet())
                .containsExactly(UnparseableReasons.NO_DATE_COLUMN, UnparseableReasons.NO_AMOUNT_COLUMN);
        assertThat(map.unparseableShapes()).containsEntry("date|narration", 3);
    }

    @Test
    void unreadableCellsOfOneFormatGroupUnderOneShapeAndNoValueReachesTheReport() throws Exception {
        // Two imports, two different dates in the same unsupported format: one backlog entry with
        // a count of two, which is the question the report exists to answer -- and neither date.
        String first = objectMapper.writeValueAsString(UnparseableRowSummary.of(List.of(new UnparseableRow(
                Map.of("date", "05-Sept-2026"), UnparseableReasons.dateUnreadable("05-Sept-2026")))));
        String second = objectMapper.writeValueAsString(UnparseableRowSummary.of(List.of(new UnparseableRow(
                Map.of("date", "17-Sept-2026"), UnparseableReasons.dateUnreadable("17-Sept-2026")))));

        var map = service.aggregate(List.of(importWith(null, first), importWith(null, second)));

        assertThat(map.unparseableReasons())
                .containsOnly(entry("Date value \"99-XXXX-9999\" didn't match any known date format", 2));
        assertThat(objectMapper.writeValueAsString(map)).doesNotContain("Sept").doesNotContain("2026");
    }

    @Test
    void summariesStoredBeforeTheFixStillAggregate() {
        // Rows written before this change are keyed by the review-screen reason; the report must
        // keep reading them rather than skipping them as unreadable. Whether those rows are
        // redacted is a separate, data-level decision.
        String legacy = "{\"reasons\":{\"" + UnparseableReasons.NO_DATE_COLUMN.replace("\"", "\\\"")
                + "\":2},\"columnSignatures\":{\"narration\":2},\"total\":2}";

        var map = service.aggregate(List.of(importWith(null, legacy)));

        assertThat(map.rowsLost()).isEqualTo(2);
        assertThat(map.unparseableReasons()).containsEntry(UnparseableReasons.NO_DATE_COLUMN, 2);
    }

    @Test
    void oneUnreadableRowNeverBreaksTheWholeReport() {
        // These are observations, not the ledger. A metrics read that 500s because one row has
        // malformed JSON is worse than a metrics read that is short by one row.
        var map = service.aggregate(List.of(
                importWith("{not json at all", null),
                importWith(capabilities("REPEATED_HEADER"), "also not json")));

        assertThat(map.activations()).containsEntry("REPEATED_HEADER", 1);
        assertThat(map.rowsLost()).isZero();
    }

    @Test
    void summariesCountShapesAndReasonsButNeverTheValues() {
        // The privacy property, asserted rather than assumed: an unparseable row is a line of
        // somebody's bank statement, and this table is read by admins and retained indefinitely.
        var summary = UnparseableRowSummary.of(List.of(
                new UnparseableRow(Map.of("narration", "UPI/XXXXX XXXX/9999999999"), UnparseableReasons.NO_DATE_COLUMN)));

        assertThat(summary.toString()).doesNotContain("XXXXX");
        assertThat(summary.toString()).doesNotContain("9999999999");
        assertThat(summary.columnSignatures()).containsOnlyKeys("narration");
    }

    @Test
    void aRowsColumnsProduceTheSameShapeRegardlessOfOrder() {
        // Otherwise one layout fragments into several apparent shapes and every count understates
        // itself.
        var first = UnparseableRowSummary.of(List.of(
                new UnparseableRow(new java.util.LinkedHashMap<>(Map.of("amount", "1", "date", "2")), "r")));
        var second = UnparseableRowSummary.of(List.of(
                new UnparseableRow(new java.util.LinkedHashMap<>(Map.of("date", "2", "amount", "1")), "r")));

        assertThat(first.columnSignatures().keySet()).isEqualTo(second.columnSignatures().keySet());
    }
}
