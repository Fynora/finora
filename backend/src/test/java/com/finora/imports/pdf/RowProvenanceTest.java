package com.finora.imports.pdf;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RowProvenanceTest {

    private static PositionedText run(String text, float x, float y, int page) {
        return new PositionedText(text, x, y, page, 10f, 0f, null, TextSource.NATIVE_PDF);
    }

    @Test
    void aPhysicalRowIsOneLineAtItsTopmostRunRoundedToHundredths() {
        RowLines lines = RowLines.of(List.of(run("a", 10, 401.204f, 1), run("b", 90, 400.996f, 1)));
        assertThat(lines.lines()).containsExactly(new RowLines.Line(1, 40100));
    }

    @Test
    void hundredthsAreWhatATraceWritesSoACopyReadsTheSameLine() {
        // The copy reading parses y back from the trace's "%.2f"; both must land on one Line.
        float real = 412.805f;
        float reparsed = Float.parseFloat(String.format(java.util.Locale.ROOT, "%.2f", real));
        assertThat(RowLines.of(List.of(run("a", 10, real, 0))))
                .isEqualTo(RowLines.of(List.of(run("a", 10, reparsed, 0))));
    }

    @Test
    void anEmptyPhysicalRowIsNone() {
        assertThat(RowLines.of(List.of())).isEqualTo(RowLines.NONE);
        assertThat(RowLines.NONE.isEmpty()).isTrue();
    }

    @Test
    void mergingUnionsInPageOrderAndDropsDuplicates() {
        RowProvenance p = new RowProvenance();
        Map<String, String> anchor = new LinkedHashMap<>();
        Map<String, String> continuation = new LinkedHashMap<>();
        p.record(anchor, List.of(run("a", 10, 412.8f, 2)));
        p.record(continuation, List.of(run("b", 10, 401.2f, 2)));
        p.record(continuation, List.of(run("c", 10, 412.8f, 2)));
        p.merge(anchor, continuation);
        assertThat(p.of(anchor).lines())
                .containsExactly(new RowLines.Line(2, 40120), new RowLines.Line(2, 41280));
    }

    @Test
    void pagesOrderBeforeY() {
        RowLines a = RowLines.of(List.of(run("a", 10, 700f, 0)));
        RowLines b = RowLines.of(List.of(run("b", 10, 80f, 1)));
        assertThat(b.union(a).lines())
                .containsExactly(new RowLines.Line(0, 70000), new RowLines.Line(1, 8000));
    }

    @Test
    void mergingFromAnUnknownRowChangesNothing() {
        RowProvenance p = new RowProvenance();
        Map<String, String> anchor = new LinkedHashMap<>();
        p.record(anchor, List.of(run("a", 10, 100f, 0)));
        p.merge(anchor, new LinkedHashMap<>());
        assertThat(p.of(anchor).lines()).containsExactly(new RowLines.Line(0, 10000));
    }

    @Test
    void identityNotEqualityKeysTheTable() {
        RowProvenance p = new RowProvenance();
        Map<String, String> a = new LinkedHashMap<>(Map.of("Description", "same"));
        Map<String, String> b = new LinkedHashMap<>(Map.of("Description", "same"));
        p.record(a, List.of(run("x", 10, 100f, 1)));
        assertThat(p.of(b)).isEqualTo(RowLines.NONE);
    }

    @Test
    void transferMovesTheRecordToAReplacementMap() {
        RowProvenance p = new RowProvenance();
        Map<String, String> before = new LinkedHashMap<>();
        Map<String, String> after = new LinkedHashMap<>();
        p.record(before, List.of(run("x", 10, 100f, 1)));
        p.transfer(before, after);
        assertThat(p.of(after).lines()).containsExactly(new RowLines.Line(1, 10000));
    }

    // A malformed PDF can put a run anywhere: the row map must never be the reason a statement
    // fails to read. A position a trace could not write as hundredths is simply not recorded.

    @Test
    void aRunAtANonFiniteOrHugePositionIsNotALine() {
        assertThat(RowLines.of(List.of(run("a", 10, Float.NaN, 0)))).isEqualTo(RowLines.NONE);
        assertThat(RowLines.of(List.of(run("a", 10, Float.POSITIVE_INFINITY, 0)))).isEqualTo(RowLines.NONE);
        assertThat(RowLines.of(List.of(run("a", 10, 1e12f, 0)))).isEqualTo(RowLines.NONE);
        assertThat(RowLines.of(List.of(run("a", 10, -1e12f, 0)))).isEqualTo(RowLines.NONE);
    }

    @Test
    void aRowWithOneBadRunStillTakesItsGoodRun() {
        assertThat(RowLines.of(List.of(run("a", 10, Float.NaN, 0), run("b", 90, 120f, 0))).lines())
                .containsExactly(new RowLines.Line(0, 12000));
        assertThat(RowLines.of(List.of(run("a", 10, -1e12f, 0), run("b", 90, 120f, 0))).lines())
                .containsExactly(new RowLines.Line(0, 12000));
    }

    @Test
    void theLargestWritablePositionIsKept() {
        assertThat(RowLines.of(List.of(run("a", 10, 1_000_000f, 3))).lines())
                .containsExactly(new RowLines.Line(3, 100_000_000));
    }
}
