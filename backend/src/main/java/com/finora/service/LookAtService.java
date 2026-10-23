package com.finora.service;

import com.finora.dto.ChangedAmountDto;
import com.finora.dto.InsightAcknowledgementDto;
import com.finora.dto.InsightsDto;
import com.finora.dto.LookAtRowDto;
import com.finora.dto.RecurringDto;
import com.finora.people.PeopleService;
import com.finora.people.PeopleSuggestions;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Insights' "To look at", built on the server (docs/superpowers/specs/2026-10-22-ai-insights-foundation-design.md
 * §8.2) so AI ranking and "Why?" know exactly which rows the user sees. A straight port of
 * mobile/src/lib/insightsFeed.ts's buildToLookAt -- same kinds, thresholds, order and "Got it" keys;
 * LookAtServiceTest holds the app's own cases. Mobile 1.0.0 still builds its own list; the two must
 * agree.
 */
@Service
public class LookAtService {

    /** A category counts as running high from this rise over its usual month: InsightsService's own
     *  mover significance threshold. */
    static final double RUNNING_HIGH_MIN_PCT = 15;
    /** ...and by at least this many rupees, so a small category's swing does not fill the list. */
    static final BigDecimal RUNNING_HIGH_MIN_RUPEES = new BigDecimal("500");
    static final int RUNNING_HIGH_MAX_ROWS = 3;
    /** "Why?" compares with the usual month only when it holds at least this many months (§4.3). */
    static final int WHY_MIN_BASELINE_MONTHS = 2;

    private final InsightsService insightsService;
    private final RecurringService recurringService;
    private final RecurringAnswerService recurringAnswerService;
    private final PeopleService peopleService;
    private final InsightAcknowledgementService acknowledgementService;

    public LookAtService(InsightsService insightsService, RecurringService recurringService,
                         RecurringAnswerService recurringAnswerService, PeopleService peopleService,
                         InsightAcknowledgementService acknowledgementService) {
        this.insightsService = insightsService;
        this.recurringService = recurringService;
        this.recurringAnswerService = recurringAnswerService;
        this.peopleService = peopleService;
        this.acknowledgementService = acknowledgementService;
    }

    /** The rows for {@code month} ({@code yyyy-MM}, or null for the newest reporting month), in rules order. */
    public List<LookAtRowDto> rows(UUID userId, String month) {
        return rows(userId, insightsService.buildDetailed(userId, month));
    }

    /** The rows for a month whose insights are already built, in rules order. */
    public List<LookAtRowDto> rows(UUID userId, InsightsService.Built built) {
        return rowsWithAll(userId, built).shown();
    }

    /** {@code shown}: the rows the user sees. {@code all}: the same rows before "Got it" took any away
     *  -- what AI insights count as facts of the month, so marking a row "Got it" never changes them
     *  (spec §5.4). People rows are already left out by PeopleService when acknowledged, in both. */
    public record Rows(List<LookAtRowDto> shown, List<LookAtRowDto> all) {}

    /** Both lists for a month whose insights are already built: the sources are read once. */
    public Rows rowsWithAll(UUID userId, InsightsService.Built built) {
        Set<String> acks = acknowledgementService.list(userId).stream()
                .map(LookAtService::ackEntry).collect(Collectors.toSet());
        List<RecurringDto> recurring = recurringService.detectForUser(userId);
        // The groups just detected, not a second detection run: it would read every row again.
        List<ChangedAmountDto> changed = recurringAnswerService.changedAmounts(userId, recurring);
        List<PeopleSuggestions.Suggestion> people = peopleService.suggestions(userId).items();
        return new Rows(build(built.dto(), recurring, changed, acks, people, built.baselineMonths()),
                build(built.dto(), recurring, changed, Set.of(), people, built.baselineMonths()));
    }

    private static String ackEntry(InsightAcknowledgementDto a) {
        return a.kind() + "|" + a.itemKey();
    }

    /**
     * buildToLookAt, ported: a card statement to upload, questions about money with people, new
     * repeating payments to answer, repeating payments whose amount moved, and categories running
     * high -- in that order, less what was marked "Got it" ({@code acks} holds {@code KIND|itemKey}).
     */
    static List<LookAtRowDto> build(InsightsDto insights, List<RecurringDto> recurring, List<ChangedAmountDto> changed,
                                    Set<String> acks, List<PeopleSuggestions.Suggestion> people,
                                    Map<String, Integer> baselineMonths) {
        List<LookAtRowDto> rows = new ArrayList<>();

        InsightsDto.UnlinkedCardBills bills = insights == null ? null : insights.unlinkedCardBills();
        if (bills != null) {
            String itemKey = bills.month();
            if (!acks.contains("CARD_STATEMENT_MISSING|" + itemKey)) {
                rows.add(LookAtRowDto.cardStatement(bills, itemKey));
            }
        }

        // The server already leaves out what was marked "Got it"; this also hides one the moment it is tapped.
        for (PeopleSuggestions.Suggestion s : people) {
            if (!acks.contains(s.kind() + "|" + s.ackKey())) rows.add(LookAtRowDto.people(s));
        }

        for (RecurringDto r : recurring) {
            if (r.state() == RecurringDto.QuestionState.NEEDS_ANSWER) {
                rows.add(LookAtRowDto.newRecurring(r, normalizeMerchant(r.merchant())));
            }
        }

        // A payee can arrive both as a detected group in AMOUNT_CHANGED state and as a changed-amounts
        // entry (a saved answer whose payee is no longer detected); show it once.
        record Change(String merchant, String category, BigDecimal latestAmount, String latestDate) {}
        List<Change> changes = new ArrayList<>();
        for (RecurringDto r : recurring) {
            if (r.state() == RecurringDto.QuestionState.AMOUNT_CHANGED) {
                changes.add(new Change(r.merchant(),
                        r.answer() != null ? r.answer() : (r.category() != null ? r.category() : ""),
                        r.latestAmount() != null ? r.latestAmount() : r.averageAmount(),
                        r.lastDate().toString()));
            }
        }
        for (ChangedAmountDto c : changed) {
            changes.add(new Change(c.merchant(), c.category(), c.latestAmount(), c.latestDate().toString()));
        }
        Set<String> seen = new HashSet<>();
        for (Change c : changes) {
            String merchantKey = normalizeMerchant(c.merchant());
            if (!seen.add(merchantKey)) continue;
            String itemKey = merchantKey + "|" + c.latestDate();
            if (acks.contains("AMOUNT_CHANGED|" + itemKey)) continue;
            rows.add(LookAtRowDto.amountChanged(c.merchant(), c.category(), c.latestAmount(), c.latestDate(), itemKey));
        }

        String month = insights == null ? null : insights.reportingMonth();
        if (month != null) {
            insights.movers().stream()
                    .filter(m -> m.pctChange() != null && m.pctChange() >= RUNNING_HIGH_MIN_PCT
                            && m.current().subtract(m.priorAverage()).compareTo(RUNNING_HIGH_MIN_RUPEES) >= 0)
                    .filter(m -> !acks.contains("CATEGORY_HIGH|" + m.category() + "|" + month))
                    .sorted(Comparator.comparing((InsightsDto.CategoryMover m) -> m.current().subtract(m.priorAverage()))
                            .reversed())
                    .limit(RUNNING_HIGH_MAX_ROWS)
                    .forEach(m -> rows.add(LookAtRowDto.runningHigh(m.category(), m.current(), m.priorAverage(), month,
                            m.category() + "|" + month,
                            baselineMonths.getOrDefault(m.category(), 0) >= WHY_MIN_BASELINE_MONTHS)));
        }
        return rows;
    }

    /**
     * The rows in a stored order: keys still present come first, in stored order; every other row
     * follows in rules order. A key no longer present (marked "Got it" since) is dropped; a row that is
     * new since the order was made is appended (spec §5.4).
     */
    static List<LookAtRowDto> applyOrder(List<LookAtRowDto> rules, List<String> storedOrder) {
        Map<String, LookAtRowDto> byKey = new LinkedHashMap<>();
        for (LookAtRowDto r : rules) byKey.put(r.key(), r);
        List<LookAtRowDto> ordered = new ArrayList<>();
        for (String key : storedOrder) {
            LookAtRowDto r = byKey.remove(key);
            if (r != null) ordered.add(r);
        }
        ordered.addAll(byKey.values());
        return ordered;
    }

    private static String normalizeMerchant(String merchant) {
        return merchant == null ? "" : merchant.trim().toLowerCase(Locale.ROOT);
    }
}
