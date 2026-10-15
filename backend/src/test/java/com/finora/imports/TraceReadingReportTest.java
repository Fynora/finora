package com.finora.imports;

import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.pdf.trace.PdfTraceRedactor;
import com.finora.imports.pdf.trace.TraceFormat;
import com.finora.imports.pdf.trace.TraceMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The Claude fix job's reading report, over a synthetic statement's blanked copy. */
class TraceReadingReportTest {

    private static String syntheticCopy() throws Exception {
        PdfPreviewGenerator generator = PdfGeneratorTestSupport.generator();
        var real = generator.acquire(PdfFixtureBuilder.buildSampleWithPlantedCustomerData(), null);
        return TraceFormat.format(PdfTraceRedactor.redact(real.runs()), new TraceMetadata(
                TraceMetadata.CURRENT_TRACE_VERSION, PdfTraceRedactor.REDACTOR_VERSION,
                PdfTraceRedactor.allowlistFingerprint(), "2026-10-15", "held item " + UUID.randomUUID(),
                List.of(), List.of(), "", List.of()));
    }

    @Test
    void listsEveryRowAndEndsWithACountsOnlySummary() throws Exception {
        String report = TraceReadingReport.read(syntheticCopy());

        List<String> lines = report.lines().toList();
        assertThat(lines.get(0)).isEqualTo("section\trow\tdate\tamount\tbalance\ttype\tdescription");
        String summary = lines.get(lines.size() - 1);
        assertThat(summary).startsWith("# summary: sections 1; rows read ").endsWith(
                "; fields date,amount,balance,type,description");
        int rows = lines.size() - 2;
        assertThat(rows).isPositive();
        assertThat(summary).contains("rows read " + rows + ";");
        // The summary is what a public PR may quote: counts and field names, never a value.
        assertThat(summary.replaceAll("[0-9]+(,[0-9]+)*", "N").replaceAll("\\+", ""))
                .doesNotContainPattern("[0-9]");
        assertThat(lines.get(1)).matches("0\t0\t\\d{4}-\\d{2}-\\d{2}\t-?[0-9.]+\t[0-9.-]*\t\\w+\t.*");
        assertThat(report).doesNotContain(PdfFixtureBuilder.PLANTED_NAME, PdfFixtureBuilder.PLANTED_ACCOUNT_NUMBER);
    }

    @Test
    void theRunnerWritesTheReportToTheFileItIsGiven(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("copy.trace");
        Path out = dir.resolve("report.tsv");
        Files.writeString(in, syntheticCopy(), StandardCharsets.UTF_8);
        System.setProperty("finora.trace.report.in", in.toString());
        System.setProperty("finora.trace.report.out", out.toString());
        try {
            new TraceReadingReport().report();
        } finally {
            System.clearProperty("finora.trace.report.in");
            System.clearProperty("finora.trace.report.out");
        }
        assertThat(Files.readString(out)).startsWith("section\trow\t").contains("# summary: ");
    }
}
