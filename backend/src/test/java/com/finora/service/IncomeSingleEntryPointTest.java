package com.finora.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Income must be decided in ONE place ({@link FlowTotals#countsAsIncome}). The dashboard, the monthly
 * report and the trend each deciding "income" by hand is how "every credit is income" survived
 * every fix to the exclusions around it. Direction is still legitimate for matching, sign and
 * balances -- those files are allow-listed by name, each with the reason. Keep this list short and
 * review every addition.
 */
class IncomeSingleEntryPointTest {

    private static final Set<String> ALLOWED = Set.of(
            "FlowClassifier.java",                         // the classifier itself
            "FlowTotals.java",                             // the choke point
            "ReconciliationService.java",                  // pairing refund/transfer legs by direction, not totals
            "AccountBalanceConvention.java",               // the sign of a balance change
            "AccountAggregatorTransactionDiffService.java", // mapping an AA credit/debit onto direction
            "Transaction.java");                           // the enum's own declaration

    @Test
    void noOtherMainSourceFiltersOnTheIncomeDirection() throws Exception {
        // Surefire runs with the backend module as the working directory.
        Path root = Path.of("src/main/java/com/finora");
        assertThat(root).isDirectory();
        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !ALLOWED.contains(p.getFileName().toString()))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains("Type.INCOME");
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(Path::toString)
                    .toList();
        }
        assertThat(offenders).as("route income through FlowTotals.countsAsIncome").isEmpty();
    }
}
