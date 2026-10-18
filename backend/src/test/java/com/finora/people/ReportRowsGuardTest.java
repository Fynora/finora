package com.finora.people;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every figure reads shares and loans through ReportRows. A caller that still builds its rows or its
 * netting the old way would show a shared bill at its full amount on that one screen (spec §5).
 */
class ReportRowsGuardTest {

    private static final Set<String> ALLOWED = Set.of("ReportRows.java", "TransactionGraphService.java", "RefundNetting.java");

    @Test
    void noCallerBypassesReportRows() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !ALLOWED.contains(p.getFileName().toString()))
                    .flatMap(p -> {
                        try {
                            return Files.readAllLines(p).stream()
                                    .filter(line -> !line.trim().startsWith("*") && !line.trim().startsWith("//"))
                                    .filter(line -> line.contains("ccPaymentFromTransactionIds(") || line.contains("RefundNetting.from("))
                                    .map(line -> p.getFileName() + ": " + line.trim());
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }).toList();
        }
        assertThat(offenders).as("use ReportRows.reportable / ReportRows.netting").isEmpty();
    }
}
