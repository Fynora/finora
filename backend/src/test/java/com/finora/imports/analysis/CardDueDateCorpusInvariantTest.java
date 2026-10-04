package com.finora.imports.analysis;

import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.imports.pdf.PdfPreviewGenerator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A credit-card bill is due after the billing period it covers has closed, so every statement that
 * stages both a payment due date and a statement period must stage the due date after the period's
 * end. Checked over the real corpus, because only real documents carry the text that breaks it.
 *
 * <p>What it guards against, measured: the line-based due-date reader once searched the lines
 * below any "due date" mention, including mentions inside terms-and-conditions sentences, and two
 * real card statements carry an old dated notice within that reach. Only a missing date format kept
 * that notice from being staged; adding the format staged a due date years before the statement
 * period, in place of the real one. The reader now skips such sentences, and this check fails if any
 * route -- that one or another -- stages a due date the period makes impossible.
 *
 * <p>Set {@code FINORA_CORPUS_DIR} to run it; it skips itself otherwise, since the corpus is real
 * customer documents and can never be committed. A statement the pipeline cannot read at all (a
 * locked PDF, for one) is reported and skipped: whether it imports is not this check's question.
 */
class CardDueDateCorpusInvariantTest {

    private static Path corpusDir() {
        String configured = System.getenv("FINORA_CORPUS_DIR");
        return configured == null || configured.isBlank() ? null : Path.of(configured);
    }

    private static List<Path> statements(Path corpus) throws IOException {
        try (Stream<Path> walk = Files.walk(corpus)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    @Test
    void everyStagedPaymentDueDateFallsAfterTheStatementPeriodItBills() throws Exception {
        Path corpus = corpusDir();
        assumeTrue(corpus != null && Files.isDirectory(corpus),
                "FINORA_CORPUS_DIR is not set to a real corpus directory -- this check only runs "
                        + "locally, because the corpus can never be committed");
        List<Path> statements = statements(corpus);
        assumeTrue(!statements.isEmpty(), "no statements found under " + corpus);

        PdfPreviewGenerator generator = ProbePipelines.productionRoutedGenerator();
        List<String> violations = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        int checked = 0;
        for (Path statement : statements) {
            String name = statement.getFileName().toString();
            List<StagedAccountSection> sections;
            try {
                sections = generator.generateSectionsWithContext(
                        UUID.randomUUID(), name, Files.readAllBytes(statement), null).sections();
            } catch (Exception e) {
                unreadable.add(name + " (" + e.getClass().getSimpleName() + ")");
                continue;
            }
            for (int i = 0; i < sections.size(); i++) {
                DetectedAccountInfo d = sections.get(i).detectedAccount();
                if (d == null || d.paymentDueDate() == null || d.statementPeriodEnd() == null) continue;
                checked++;
                if (!d.paymentDueDate().isAfter(d.statementPeriodEnd())) {
                    violations.add(name + " section " + i + ": due " + d.paymentDueDate()
                            + ", period ends " + d.statementPeriodEnd());
                }
            }
        }

        System.out.println("card due-date check: " + checked + " section(s) checked over "
                + statements.size() + " statement(s); unreadable, skipped: " + unreadable);
        assertThat(checked)
                .as("no statement staged both a due date and a period, so nothing was checked")
                .isPositive();
        assertThat(violations)
                .as("a payment due date on or before the end of the period it bills")
                .isEmpty();
    }
}
