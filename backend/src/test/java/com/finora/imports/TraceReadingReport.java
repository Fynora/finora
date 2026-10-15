package com.finora.imports;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.acquisition.AcquiredDocument;
import com.finora.imports.pdf.trace.TraceFormat;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A row-by-row reading of one trace, for the automatic Claude fix job's PRs
 * (docs/superpowers/specs/2026-10-15-claude-fix-job-design.md, section 3). The private repository's
 * {@code scripts/reading-report.sh} runs it before and after Claude's change, and the private PR
 * shows both.
 *
 * <p>Not a test: its name ends in neither "Test" nor "Tests", so surefire's includes skip it, and
 * it does nothing unless both properties are set. Run it with
 * {@code ./mvnw test -Dtest=TraceReadingReport -Dsurefire.failIfNoSpecifiedTests=false
 * -Dfinora.trace.report.in=<trace> -Dfinora.trace.report.out=<file>}.
 *
 * <p>The report is written to a file, never printed: it holds the trace's own amounts and dates,
 * which belong in the private repository only. The reading uses a fresh random user id and a
 * neutral file name, as the blanked-copy check does, so no customer's rules or merchants are read.
 * The last line is a summary of counts and field names only, the one line a public PR may quote.
 */
class TraceReadingReport {

    static final String FIELDS = "date\tamount\tbalance\ttype\tdescription";

    @Test
    void report() throws IOException {
        String in = System.getProperty("finora.trace.report.in");
        String out = System.getProperty("finora.trace.report.out");
        Assumptions.assumeTrue(in != null && out != null, "finora.trace.report.in and .out are not set");
        Files.writeString(Path.of(out), read(Files.readString(Path.of(in), StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
    }

    /** The report for one trace's text. */
    static String read(String trace) {
        PdfPreviewGenerator generator = PdfGeneratorTestSupport.generator();
        List<StagedAccountSection> sections = generator.generateSectionsFromAcquired(UUID.randomUUID(),
                "statement.pdf", AcquiredDocument.of(TraceFormat.parse(trace))).sections();
        StringBuilder report = new StringBuilder("section\trow\t" + FIELDS + "\n");
        int rows = 0;
        int unparseable = 0;
        List<String> perSection = new ArrayList<>();
        for (int s = 0; s < sections.size(); s++) {
            StagedAccountSection section = sections.get(s);
            List<StagedRow> sectionRows = section.rows() == null ? List.of() : section.rows();
            for (int r = 0; r < sectionRows.size(); r++) {
                StagedRow row = sectionRows.get(r);
                report.append(s).append('\t').append(r).append('\t')
                        .append(row.date()).append('\t')
                        .append(plain(row.amount())).append('\t')
                        .append(plain(row.balanceAfter())).append('\t')
                        .append(row.type()).append('\t')
                        .append(row.description() == null ? "" : row.description().replace('\t', ' ').replace('\n', ' '))
                        .append('\n');
            }
            int sectionUnparseable = section.unparseableRows() == null ? 0 : section.unparseableRows().size();
            rows += sectionRows.size();
            unparseable += sectionUnparseable;
            perSection.add(sectionRows.size() + "+" + sectionUnparseable);
        }
        report.append("# summary: sections ").append(sections.size())
                .append("; rows read ").append(rows)
                .append("; unparseable rows ").append(unparseable)
                .append("; rows+unparseable per section ").append(String.join(",", perSection))
                .append("; fields ").append(FIELDS.replace('\t', ',')).append('\n');
        return report.toString();
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }
}
