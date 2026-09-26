package com.finora.imports.analysis;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.imports.product.FinancialProductType;
import com.finora.service.FlowClassifier;
import com.finora.util.CategoryRules;
import com.finora.util.CounterpartyClassifier;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Plan 0 of the financial-flow program (docs/superpowers/plans/2026-09-26-financial-flow-classification.md):
 * across the real, out-of-tree statement corpus, how much credit VALUE lands in each flow class, which
 * counterparties drive it, how hard each statement's income is cut -- and how much volume the
 * candidate mechanisms of docs/proposals/financial-flow-edge-scenarios.md would touch.
 *
 * <p>Single-statement view: no reconciliation context, so paired transfers and matched refunds are
 * absent and every other class is an upper bound; a lending cycle longer than one statement is
 * missed, so the ledger figure is a lower bound. No OCR: an image-only scan yields no rows here.
 *
 * <p>Output names real counterparties (merchant tokens). It must stay outside the repository.
 * Run manually; not a {@code @Test}, same as {@link CorpusProbe}.
 */
public final class FlowClassCorpusProbe {

    // Exploratory parameters (printed in the header): the output is what calibrates them later.
    static final BigDecimal LEDGER_MIN_GROSS = new BigDecimal("5000");
    static final BigDecimal LEDGER_MAX_NET_SHARE = new BigDecimal("0.2");
    static final int CASH_WINDOW_DAYS = 30;
    static final int PASS_WINDOW_DAYS = 14;
    static final BigDecimal PASS_TOLERANCE = new BigDecimal("0.05");
    static final BigDecimal PASS_MIN_AMOUNT = new BigDecimal("2000");

    private static final Map<String, BigDecimal[]> TOTAL = new TreeMap<>();
    private static final Map<String, BigDecimal[]> REMOVED_BY_TOKEN = new HashMap<>();
    private static final List<BigDecimal> PER_STATEMENT_CUT_PCT = new ArrayList<>();
    /** Same cuts, split by whether the statement is a card statement -- a card's cut is 100% by
     *  design (no card credit is earned income), so one pooled median hides the savings picture. */
    private static final Map<String, List<BigDecimal>> CUT_PCT_BY_KIND = new TreeMap<>();
    private static int rowsAffected = 0;
    // mechanism -> {txns, value, sections}
    private static final Map<String, BigDecimal[]> MECHANISMS = new java.util.LinkedHashMap<>();
    private static final Map<FlowPatternAnalysis.Rhythm, Integer> RHYTHMS = new EnumMap<>(FlowPatternAnalysis.Rhythm.class);
    private static final List<String> FAILED = new ArrayList<>();

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: FlowClassCorpusProbe <path-to.pdf> [<path-to.pdf> ...]");
            System.exit(2);
        }
        System.out.println("# FlowClassCorpusProbe classifier v" + FlowClassifier.VERSION);
        System.out.println("# single-statement view: LINKED_REFUND / OWN_ACCOUNT_TRANSFER cannot appear; classes are upper bounds;"
                + " ledger is a lower bound; no OCR");
        System.out.printf("# params: ledger minGross=%s maxNetShare=%s | cash window=%dd | pass-through window=%dd tolerance=%s min=%s%n",
                LEDGER_MIN_GROSS, LEDGER_MAX_NET_SHARE, CASH_WINDOW_DAYS, PASS_WINDOW_DAYS, PASS_TOLERANCE, PASS_MIN_AMOUNT);
        MECHANISMS.put("counterparty ledger candidates", zero3());
        MECHANISMS.put("cash round-trip deposits", zero3());
        MECHANISMS.put("pass-through groups (exploratory)", zero3());

        for (String arg : args) {
            Path pdf = Path.of(arg);
            try {
                probeOne(pdf);
            } catch (Exception | Error e) {
                FAILED.add(pdf.getFileName() + ": " + e.getClass().getSimpleName());
                System.out.println("  FAILED " + pdf.getFileName() + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }
        printSummary();
        System.exit(0);
    }

    static void probeOne(Path pdf) throws Exception {
        byte[] bytes = Files.readAllBytes(pdf);
        List<StagedAccountSection> sections = ProbePipelines.standardGenerator()
                .generateSectionsWithContext(UUID.randomUUID(), pdf.getFileName().toString(), bytes, null).sections();
        String file = pdf.getFileName().toString();
        System.out.println("== " + file + ": " + sections.size() + " section(s)");

        boolean cardStatement = sections.stream().anyMatch(sec -> accountTypeOf(sec) == Account.Type.CREDIT_CARD);
        BigDecimal statementCredits = BigDecimal.ZERO;
        BigDecimal statementKept = BigDecimal.ZERO;
        for (int s = 0; s < sections.size(); s++) {
            StagedAccountSection section = sections.get(s);
            Account.Type accountType = accountTypeOf(section);
            List<FlowPatternAnalysis.Row> patternRows = new ArrayList<>();
            int credits = 0, debits = 0;
            for (StagedRow row : section.rows()) {
                if (row.amount() == null || row.date() == null) continue;
                boolean credit = "INCOME".equals(row.type());
                patternRows.add(new FlowPatternAnalysis.Row(row.date(), row.amount(), credit, row.description()));
                if (!credit) { debits++; continue; }
                credits++;
                Transaction t = new Transaction();
                t.setTxnType(Transaction.Type.INCOME);
                t.setAmount(row.amount());
                t.setDescription(row.description());
                t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
                t.setCounterpartyType(CounterpartyClassifier.classify(row.description()));
                FlowClassifier.FlowDecision d = FlowClassifier.classify(t, accountType);
                String key = accountType + " " + d.flowClass() + "/" + d.reason();
                merge(TOTAL, key, row.amount());
                statementCredits = statementCredits.add(row.amount());
                String token = CategoryRules.extractMerchant(row.description());
                if (d.flowClass() == FlowClassifier.FlowClass.INCOME) {
                    statementKept = statementKept.add(row.amount());
                } else {
                    rowsAffected++;
                    merge(REMOVED_BY_TOKEN, d.flowClass() + "/" + d.reason() + " " + token, row.amount());
                }
                System.out.printf("  row %-45s %12s  %s%n", key, row.amount().toPlainString(), token);
            }
            System.out.printf("  section[%d] type=%s credits=%d debits=%d%n", s, accountType, credits, debits);
            analysePatterns(s, patternRows);
        }
        if (statementCredits.signum() > 0) {
            BigDecimal cut = statementCredits.subtract(statementKept).multiply(BigDecimal.valueOf(100))
                    .divide(statementCredits, 1, RoundingMode.HALF_UP);
            PER_STATEMENT_CUT_PCT.add(cut);
            CUT_PCT_BY_KIND.computeIfAbsent(cardStatement ? "card" : "non-card", k -> new ArrayList<>()).add(cut);
            System.out.printf("  statement credits=%s keptAsIncome=%s cut=%s%%%n", statementCredits.toPlainString(),
                    statementKept.toPlainString(), pct(statementCredits.subtract(statementKept), statementCredits));
        }
    }

    private static void analysePatterns(int s, List<FlowPatternAnalysis.Row> rows) {
        // Rhythm of person credits.
        FlowPatternAnalysis.RhythmStats r = FlowPatternAnalysis.personCreditRhythm(rows);
        RHYTHMS.merge(r.rhythm(), 1, Integer::sum);
        System.out.printf("  section[%d] rhythm=%s creditsPerActiveWeek=%.1f payersPerActiveWeek=%.1f median=%s cv=%.2f activeWeeks=%d/%d%n",
                s, r.rhythm(), r.creditsPerActiveWeek(), r.payersPerActiveWeek(), r.median().toPlainString(),
                r.coefficientOfVariation(), r.activeWeeks(), r.spanWeeks());

        // Distinct person payers and the credit:debit shape (trader / landlord data).
        long payers = rows.stream().filter(FlowPatternAnalysis.Row::credit).filter(FlowPatternAnalysis.Row::person)
                .map(FlowPatternAnalysis.Row::counterpartyKey).filter(java.util.Objects::nonNull).distinct().count();
        System.out.printf("  section[%d] distinctPersonPayers=%d%n", s, payers);

        // Counterparty ledger.
        List<FlowPatternAnalysis.Position> ledger = FlowPatternAnalysis.ledgerCandidates(
                FlowPatternAnalysis.personPositions(rows), LEDGER_MIN_GROSS, LEDGER_MAX_NET_SHARE);
        for (FlowPatternAnalysis.Position p : ledger) {
            System.out.printf("  section[%d] ledger %-30s out=%s in=%s net=%s n=%d/%d span=%s..%s%n", s,
                    CategoryRules.extractMerchant(p.key().replaceFirst("^(vpa|name):", "")),
                    p.out().toPlainString(), p.in().toPlainString(), p.net().toPlainString(),
                    p.outCount(), p.inCount(), p.first(), p.last());
        }
        addMechanism("counterparty ledger candidates",
                ledger.stream().mapToInt(p -> p.outCount() + p.inCount()).sum(),
                ledger.stream().map(FlowPatternAnalysis.Position::gross).reduce(BigDecimal.ZERO, BigDecimal::add));

        // Cash round-trips.
        List<FlowPatternAnalysis.Row> cash = FlowPatternAnalysis.cashRoundTripDeposits(rows, CASH_WINDOW_DAYS);
        addMechanism("cash round-trip deposits", cash.size(),
                cash.stream().map(FlowPatternAnalysis.Row::amount).reduce(BigDecimal.ZERO, BigDecimal::add));

        // Pass-through: count each row once even if it sits in several groups.
        Set<FlowPatternAnalysis.Row> passRows = Collections.newSetFromMap(new IdentityHashMap<>());
        FlowPatternAnalysis.passThroughs(rows, PASS_WINDOW_DAYS, PASS_TOLERANCE, PASS_MIN_AMOUNT).forEach(passRows::addAll);
        addMechanism("pass-through groups (exploratory)", passRows.size(),
                passRows.stream().map(FlowPatternAnalysis.Row::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static void printSummary() {
        System.out.println();
        System.out.println("== TOTAL (count, value) by account-type class/reason");
        TOTAL.forEach((k, v) -> System.out.printf("  %-50s %6s  %15s%n", k, v[0].toPlainString(), v[1].toPlainString()));

        BigDecimal allCredits = TOTAL.values().stream().map(v -> v[1]).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal stillIncome = TOTAL.entrySet().stream().filter(e -> e.getKey().contains(" INCOME/"))
                .map(e -> e.getValue()[1]).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal removed = allCredits.subtract(stillIncome);
        System.out.printf("== HEADLINE credits (old income) %s | new income %s | removed %s (%s%%) | rows affected %d%n",
                allCredits.toPlainString(), stillIncome.toPlainString(), removed.toPlainString(),
                pct(removed, allCredits), rowsAffected);

        Map<String, BigDecimal[]> removedByClass = new TreeMap<>();
        TOTAL.forEach((k, v) -> {
            if (!k.contains(" INCOME/")) merge(removedByClass, k.substring(k.indexOf(' ') + 1), v[1], v[0]);
        });
        System.out.println("== REMOVED by class/reason (count, value, share of removed)");
        removedByClass.entrySet().stream().sorted((a, b) -> b.getValue()[1].compareTo(a.getValue()[1]))
                .forEach(e -> System.out.printf("  %-40s %6s  %15s  %s%%%n", e.getKey(),
                        e.getValue()[0].toPlainString(), e.getValue()[1].toPlainString(), pct(e.getValue()[1], removed)));

        List<BigDecimal> cuts = PER_STATEMENT_CUT_PCT.stream().sorted().toList();
        long hit = cuts.stream().filter(c -> c.signum() > 0).count();
        System.out.printf("== SEVERITY statements with credits %d | affected %d | median cut %s%% | p95 cut %s%% | max cut %s%%%n",
                cuts.size(), hit, percentile(cuts, 50), percentile(cuts, 95),
                cuts.isEmpty() ? "-" : cuts.get(cuts.size() - 1).toPlainString());
        CUT_PCT_BY_KIND.forEach((kind, list) -> {
            List<BigDecimal> sorted = list.stream().sorted().toList();
            System.out.printf("   %-8s statements %d | affected %d | median cut %s%% | p95 cut %s%% | max cut %s%%%n", kind,
                    sorted.size(), sorted.stream().filter(c -> c.signum() > 0).count(), percentile(sorted, 50),
                    percentile(sorted, 95), sorted.get(sorted.size() - 1).toPlainString());
        });

        System.out.println("== TOP 25 counterparties removed from income (class/reason token, count, value) -- NOT for the repo");
        REMOVED_BY_TOKEN.entrySet().stream().sorted((a, b) -> b.getValue()[1].compareTo(a.getValue()[1])).limit(25)
                .forEach(e -> System.out.printf("  %-60s %6s  %15s%n", e.getKey(),
                        e.getValue()[0].toPlainString(), e.getValue()[1].toPlainString()));

        System.out.println("== MECHANISM SUMMARY (txns | value | sections | users) -- within-statement; ledger is a lower bound");
        MECHANISMS.forEach((k, v) -> System.out.printf("  %-36s %6s | %15s | %4s | n/a%n", k,
                v[0].toPlainString(), v[1].toPlainString(), v[2].toPlainString()));
        System.out.println("  rhythm sections: " + RHYTHMS);
        System.out.println("== FAILED statements: " + (FAILED.isEmpty() ? "none" : FAILED));
    }

    private static Account.Type accountTypeOf(StagedAccountSection section) {
        String product = section.detectedAccount() == null ? null : section.detectedAccount().detectedProduct();
        if (product == null) return null;
        try {
            return FinancialProductType.valueOf(product).accountType();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void addMechanism(String name, int txns, BigDecimal value) {
        if (txns == 0) return;
        BigDecimal[] v = MECHANISMS.get(name);
        v[0] = v[0].add(BigDecimal.valueOf(txns));
        v[1] = v[1].add(value);
        v[2] = v[2].add(BigDecimal.ONE);
    }

    private static void merge(Map<String, BigDecimal[]> map, String key, BigDecimal amount) {
        merge(map, key, amount, BigDecimal.ONE);
    }

    private static void merge(Map<String, BigDecimal[]> map, String key, BigDecimal amount, BigDecimal count) {
        map.merge(key, new BigDecimal[]{count, amount}, (a, b) -> new BigDecimal[]{a[0].add(b[0]), a[1].add(b[1])});
    }

    private static BigDecimal[] zero3() {
        return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    }

    static String pct(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? "-" : part.multiply(BigDecimal.valueOf(100))
                .divide(whole, 1, RoundingMode.HALF_UP).toPlainString();
    }

    /** Nearest-rank percentile over an ascending list; "-" when empty. */
    static String percentile(List<BigDecimal> ascending, int p) {
        if (ascending.isEmpty()) return "-";
        int rank = (int) Math.ceil(p / 100.0 * ascending.size());
        return ascending.get(Math.max(0, rank - 1)).toPlainString();
    }

    private FlowClassCorpusProbe() {}
}
