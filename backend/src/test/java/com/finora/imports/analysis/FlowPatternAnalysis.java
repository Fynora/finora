package com.finora.imports.analysis;

import com.finora.util.CategoryRules;
import com.finora.util.CounterpartyClassifier;
import com.finora.util.CounterpartyIdentity;
import com.finora.util.CounterpartyType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Measurements for the candidate mechanisms in docs/proposals/financial-flow-edge-scenarios.md.
 * Pure: rows in, findings out. Every threshold is a caller-supplied EXPLORATORY parameter -- the
 * probe prints the values it used -- because the point of this class is to produce the data those
 * thresholds get calibrated from.
 */
final class FlowPatternAnalysis {

    private FlowPatternAnalysis() {}

    record Row(LocalDate date, BigDecimal amount, boolean credit, String description) {
        CounterpartyType counterpartyType() { return CounterpartyClassifier.classify(description); }
        String counterpartyKey() { return CounterpartyIdentity.keyOf(description); }
        boolean person() { return counterpartyType() == CounterpartyType.PERSON; }
    }

    record Position(String key, BigDecimal out, BigDecimal in, int outCount, int inCount, LocalDate first, LocalDate last) {
        BigDecimal net() { return in.subtract(out); }
        BigDecimal gross() { return in.add(out); }
        boolean bothDirections() { return outCount > 0 && inCount > 0; }
    }

    /** Per person counterparty key: money out, money in, counts and date span. Rows with no key are skipped. */
    static List<Position> personPositions(List<Row> rows) {
        Map<String, Position> byKey = new LinkedHashMap<>();
        for (Row r : rows) {
            if (!r.person()) continue;
            String key = r.counterpartyKey();
            if (key == null) continue;
            Position p = byKey.getOrDefault(key, new Position(key, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, r.date(), r.date()));
            byKey.put(key, new Position(key,
                    r.credit() ? p.out() : p.out().add(r.amount()),
                    r.credit() ? p.in().add(r.amount()) : p.in(),
                    r.credit() ? p.outCount() : p.outCount() + 1,
                    r.credit() ? p.inCount() + 1 : p.inCount(),
                    r.date().isBefore(p.first()) ? r.date() : p.first(),
                    r.date().isAfter(p.last()) ? r.date() : p.last()));
        }
        return new ArrayList<>(byKey.values());
    }

    /** Both directions present, gross at least {@code minGross}, and |net| no more than
     *  {@code maxNetShare} of the larger side -- money that mostly came back. Largest gross first. */
    static List<Position> ledgerCandidates(List<Position> positions, BigDecimal minGross, BigDecimal maxNetShare) {
        return positions.stream()
                .filter(Position::bothDirections)
                .filter(p -> p.gross().compareTo(minGross) >= 0)
                .filter(p -> p.net().abs().compareTo(p.out().max(p.in()).multiply(maxNetShare)) <= 0)
                .sorted(Comparator.comparing(Position::gross).reversed())
                .toList();
    }

    enum Rhythm { NONE, ONE_OFF, BURST, MONTHLY, STEADY, MIXED }

    record RhythmStats(Rhythm rhythm, double creditsPerActiveWeek, double payersPerActiveWeek, BigDecimal median,
                       double coefficientOfVariation, int activeWeeks, int spanWeeks) {}

    /**
     * Shape of person-to-person CREDITS over the statement's span (the span uses every row, so a
     * statement with a burst in week 1 and nothing after still spans all its weeks).
     * <ul>
     *   <li>BURST: at least 5 credits and at least 70% of them inside one 7-day bucket</li>
     *   <li>STEADY: at least 5 distinct payers, active in at least 60% of the span's weeks</li>
     *   <li>MONTHLY: at least 3 credits and no payer credits twice within 25 days</li>
     *   <li>ONE_OFF: 1-2 credits; NONE: no credits; MIXED: anything else</li>
     * </ul>
     */
    static RhythmStats personCreditRhythm(List<Row> rows) {
        List<Row> credits = rows.stream().filter(Row::credit).filter(Row::person).toList();
        if (credits.isEmpty()) return new RhythmStats(Rhythm.NONE, 0, 0, BigDecimal.ZERO, 0, 0, 0);
        LocalDate start = rows.stream().map(Row::date).min(LocalDate::compareTo).orElseThrow();
        LocalDate end = rows.stream().map(Row::date).max(LocalDate::compareTo).orElseThrow();
        int spanWeeks = (int) (ChronoUnit.DAYS.between(start, end) / 7) + 1;

        Map<Long, List<Row>> byWeek = new HashMap<>();
        for (Row r : credits) byWeek.computeIfAbsent(ChronoUnit.DAYS.between(start, r.date()) / 7, w -> new ArrayList<>()).add(r);
        int activeWeeks = byWeek.size();
        double creditsPerWeek = (double) credits.size() / activeWeeks;
        double payersPerWeek = byWeek.values().stream()
                .mapToInt(ws -> (int) ws.stream().map(r -> String.valueOf(r.counterpartyKey())).distinct().count())
                .average().orElse(0);
        List<BigDecimal> amounts = credits.stream().map(Row::amount).sorted().toList();
        BigDecimal median = amounts.get((amounts.size() - 1) / 2);
        double mean = amounts.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        double sd = Math.sqrt(amounts.stream().mapToDouble(a -> Math.pow(a.doubleValue() - mean, 2)).average().orElse(0));
        double cv = mean == 0 ? 0 : sd / mean;
        Set<String> payers = new HashSet<>();
        credits.forEach(r -> payers.add(String.valueOf(r.counterpartyKey())));

        Rhythm rhythm;
        int largestWeek = byWeek.values().stream().mapToInt(List::size).max().orElse(0);
        if (credits.size() <= 2) rhythm = Rhythm.ONE_OFF;
        else if (credits.size() >= 5 && largestWeek >= 0.7 * credits.size()) rhythm = Rhythm.BURST;
        else if (payers.size() >= 5 && activeWeeks >= 0.6 * spanWeeks) rhythm = Rhythm.STEADY;
        else if (noPayerRepeatsWithin(credits, 25)) rhythm = Rhythm.MONTHLY;
        else rhythm = Rhythm.MIXED;
        return new RhythmStats(rhythm, creditsPerWeek, payersPerWeek, median, cv, activeWeeks, spanWeeks);
    }

    private static boolean noPayerRepeatsWithin(List<Row> credits, int days) {
        Map<String, LocalDate> last = new HashMap<>();
        for (Row r : credits.stream().sorted(Comparator.comparing(Row::date)).toList()) {
            LocalDate prev = last.put(String.valueOf(r.counterpartyKey()), r.date());
            if (prev != null && ChronoUnit.DAYS.between(prev, r.date()) < days) return false;
        }
        return true;
    }

    static final List<String> CASH_DEPOSIT_KEYWORDS = List.of("cash deposit", "cash dep", "by cash", "cdm");

    /** Cash deposits that fit inside unspent, earlier ATM withdrawals (FIFO pool, each withdrawal
     *  usable for {@code windowDays}). A deposit larger than what is left in the pool is not a round-trip. */
    static List<Row> cashRoundTripDeposits(List<Row> rows, int windowDays) {
        List<Row> sorted = rows.stream().sorted(Comparator.comparing(Row::date)).toList();
        List<Object[]> pool = new ArrayList<>(); // {date, remaining}
        List<Row> matched = new ArrayList<>();
        for (Row r : sorted) {
            if (!r.credit() && "Cash Withdrawal".equals(CategoryRules.suggestCategory(r.description()))) {
                pool.add(new Object[]{r.date(), r.amount()});
                continue;
            }
            if (!r.credit()) continue;
            String text = " " + CategoryRules.normalize(r.description()) + " ";
            if (CASH_DEPOSIT_KEYWORDS.stream().noneMatch(k -> text.contains(" " + k))) continue;
            pool.removeIf(p -> ChronoUnit.DAYS.between((LocalDate) p[0], r.date()) > windowDays);
            BigDecimal available = pool.stream().map(p -> (BigDecimal) p[1]).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (r.amount().compareTo(available) > 0) continue;
            BigDecimal need = r.amount();
            for (Object[] p : pool) {
                BigDecimal take = need.min((BigDecimal) p[1]);
                p[1] = ((BigDecimal) p[1]).subtract(take);
                need = need.subtract(take);
                if (need.signum() == 0) break;
            }
            matched.add(r);
        }
        return matched;
    }

    /**
     * Collector shape: one debit of at least {@code minAmount} whose preceding {@code windowDays} hold
     * two or more person credits summing to within {@code tolerance} (a share, e.g. 0.05) of it -- and
     * the mirror (one credit, then two or more person debits). Exploratory: expect false positives.
     */
    static List<List<Row>> passThroughs(List<Row> rows, int windowDays, BigDecimal tolerance, BigDecimal minAmount) {
        List<List<Row>> found = new ArrayList<>();
        for (Row anchor : rows) {
            if (anchor.amount().compareTo(minAmount) < 0) continue;
            List<Row> legs = rows.stream()
                    .filter(r -> r.credit() != anchor.credit() && r.person())
                    .filter(r -> anchor.credit()
                            ? !r.date().isBefore(anchor.date()) && ChronoUnit.DAYS.between(anchor.date(), r.date()) <= windowDays
                            : !r.date().isAfter(anchor.date()) && ChronoUnit.DAYS.between(r.date(), anchor.date()) <= windowDays)
                    .toList();
            if (legs.size() < 2) continue;
            BigDecimal sum = legs.stream().map(Row::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal gap = sum.subtract(anchor.amount()).abs();
            if (gap.compareTo(anchor.amount().multiply(tolerance).setScale(2, RoundingMode.HALF_UP)) <= 0) {
                List<Row> group = new ArrayList<>(legs);
                group.add(anchor);
                found.add(group);
            }
        }
        return found;
    }
}
