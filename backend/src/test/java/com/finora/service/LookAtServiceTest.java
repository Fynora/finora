package com.finora.service;

import com.finora.dto.ChangedAmountDto;
import com.finora.dto.InsightsDto;
import com.finora.dto.LookAtRowDto;
import com.finora.dto.RecurringDto;
import com.finora.people.PeopleSuggestions.Suggestion;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "To look at" list built on the server (AI insights foundation §8.2). Every case below is a port
 * of mobile/src/lib/insightsFeed.test.ts's buildToLookAt cases, with the same inputs and the same
 * expected kinds, order and keys -- the server must give exactly the list the app builds today.
 * Every name and amount is invented.
 */
class LookAtServiceTest {

    private static final Map<String, Integer> BASELINES = Map.of();

    private static InsightsDto insights(List<InsightsDto.CategoryMover> movers, String month,
                                        InsightsDto.UnlinkedCardBills bills) {
        return new InsightsDto(List.of(), movers, null, null, null, month, false, bills);
    }

    private static InsightsDto insights() {
        return insights(List.of(), "2026-09", null);
    }

    private static InsightsDto.CategoryMover mover(String category, String current, String prior, Double pct) {
        return new InsightsDto.CategoryMover(category, new BigDecimal(current), new BigDecimal(prior), pct);
    }

    private static RecurringDto recurring(String merchant, RecurringDto.QuestionState state, String category,
                                          String latestAmount, String lastDate) {
        return new RecurringDto(merchant, "Monthly", new BigDecimal("1500"), 3, LocalDate.parse(lastDate),
                LocalDate.parse(lastDate).plusMonths(1), category,
                latestAmount == null ? null : new BigDecimal(latestAmount), null, state);
    }

    private static RecurringDto recurring(RecurringDto.QuestionState state) {
        return recurring("sample gym", state, null, null, "2026-09-05");
    }

    private static Suggestion suggestion(String kind, String ackKey) {
        return new Suggestion(kind, ackKey, UUID.randomUUID(), null, "vpa:flatmate.a", "Flatmate A",
                new BigDecimal("9000"), LocalDate.parse("2026-10-04"), "Rent", List.of(), null);
    }

    private static List<LookAtRowDto> build(InsightsDto insights, List<RecurringDto> recurring,
                                            List<ChangedAmountDto> changed, Set<String> acks, List<Suggestion> people) {
        return LookAtService.build(insights, recurring, changed, acks, people, BASELINES);
    }

    // ---- running high ----

    @Test
    void keepsACategoryAtLeast15PercentAndAtLeast500AboveItsUsualMonth() {
        List<LookAtRowDto> rows = build(insights(List.of(
                mover("Food", "9400", "6300", 49.0),
                mover("Fuel", "800", "400", 100.0),
                mover("Rent", "20000", "18000", 11.0),
                mover("New", "3000", "0", null)), "2026-09", null), List.of(), List.of(), Set.of(), List.of());

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.kind()).isEqualTo("running-high");
            assertThat(r.category()).isEqualTo("Food");
            assertThat(r.current()).isEqualByComparingTo("9400");
            assertThat(r.priorAverage()).isEqualByComparingTo("6300");
            assertThat(r.month()).isEqualTo("2026-09");
            assertThat(r.ackKind()).isEqualTo("CATEGORY_HIGH");
            assertThat(r.ackItemKey()).isEqualTo("Food|2026-09");
            assertThat(r.key()).isEqualTo("CATEGORY_HIGH|Food|2026-09");
        });
    }

    @Test
    void showsAtMostThreeTheLargestRupeeGapFirst() {
        List<LookAtRowDto> rows = build(insights(List.of(
                mover("A", "2000", "1000", 100.0),
                mover("B", "9000", "3000", 200.0),
                mover("C", "5000", "2000", 150.0),
                mover("D", "4000", "2500", 60.0)), "2026-09", null), List.of(), List.of(), Set.of(), List.of());

        assertThat(rows).extracting(LookAtRowDto::category).containsExactly("B", "C", "D");
    }

    @Test
    void isExactlyAtTheThresholds15PercentAnd500AreEnough() {
        List<LookAtRowDto> rows = build(insights(List.of(mover("Edge", "3833.34", "3333.34", 15.0)), "2026-09", null),
                List.of(), List.of(), Set.of(), List.of());
        assertThat(rows).hasSize(1);
    }

    @Test
    void hidesACategoryMarkedGotItForThatMonthOnly() {
        List<InsightsDto.CategoryMover> movers = List.of(mover("Food", "9400", "6300", 49.0));
        Set<String> acks = Set.of("CATEGORY_HIGH|Food|2026-09");
        assertThat(build(insights(movers, "2026-09", null), List.of(), List.of(), acks, List.of())).isEmpty();
        assertThat(build(insights(movers, "2026-10", null), List.of(), List.of(), acks, List.of())).hasSize(1);
    }

    @Test
    void saysNothingAboutAMonthItCannotName() {
        assertThat(build(insights(List.of(mover("Food", "9400", "6300", 49.0)), null, null),
                List.of(), List.of(), Set.of(), List.of())).isEmpty();
    }

    // ---- repeating payments ----

    @Test
    void asksAboutANewRepeatingPaymentWhichIsAnsweredNeverDismissed() {
        RecurringDto item = recurring(RecurringDto.QuestionState.NEEDS_ANSWER);
        List<LookAtRowDto> rows = build(insights(),
                List.of(item, recurring("other", RecurringDto.QuestionState.ANSWERED, null, null, "2026-09-05")),
                List.of(), Set.of(), List.of());
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.kind()).isEqualTo("new-recurring");
            assertThat(r.recurring()).isEqualTo(item);
            assertThat(r.key()).isEqualTo("NEW_RECURRING|sample gym");
        });
    }

    @Test
    void showsAnAmountChangeOnceWhenBothListsReportTheSamePayee() {
        RecurringDto fromList = recurring("Sample TV", RecurringDto.QuestionState.AMOUNT_CHANGED, "Subscriptions",
                "649", "2026-09-12");
        List<ChangedAmountDto> changed = List.of(
                new ChangedAmountDto(" sample tv ", "Subscriptions", new BigDecimal("649"), LocalDate.parse("2026-09-12"),
                        new BigDecimal("400"), new BigDecimal("500")),
                new ChangedAmountDto("Sample Music", "Subscriptions", new BigDecimal("199"), LocalDate.parse("2026-09-02"),
                        new BigDecimal("100"), new BigDecimal("120")));

        List<LookAtRowDto> rows = build(insights(), List.of(fromList), changed, Set.of(), List.of());

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).kind()).isEqualTo("amount-changed");
        assertThat(rows.get(0).merchant()).isEqualTo("Sample TV");
        assertThat(rows.get(0).latestAmount()).isEqualByComparingTo("649");
        assertThat(rows.get(0).latestDate()).isEqualTo("2026-09-12");
        assertThat(rows.get(0).ackItemKey()).isEqualTo("sample tv|2026-09-12");
        assertThat(rows.get(1).merchant()).isEqualTo("Sample Music");
        assertThat(rows.get(1).ackKind()).isEqualTo("AMOUNT_CHANGED");
        assertThat(rows.get(1).ackItemKey()).isEqualTo("sample music|2026-09-02");
    }

    @Test
    void hidesAnAmountChangeMarkedGotItUntilALaterPaymentMovesAgain() {
        ChangedAmountDto change = new ChangedAmountDto("Sample TV", "Subscriptions", new BigDecimal("649"),
                LocalDate.parse("2026-09-12"), new BigDecimal("400"), new BigDecimal("500"));
        Set<String> acks = Set.of("AMOUNT_CHANGED|sample tv|2026-09-12");
        assertThat(build(insights(), List.of(), List.of(change), acks, List.of())).isEmpty();
        ChangedAmountDto later = new ChangedAmountDto("Sample TV", "Subscriptions", new BigDecimal("649"),
                LocalDate.parse("2026-10-12"), new BigDecimal("400"), new BigDecimal("500"));
        assertThat(build(insights(), List.of(), List.of(later), acks, List.of())).hasSize(1);
    }

    // ---- card statement missing ----

    private static final InsightsDto.UnlinkedCardBills BILLS =
            new InsightsDto.UnlinkedCardBills("2026-09", new BigDecimal("21400"), 2, "Sample Card", "0001");

    @Test
    void cardStatementAppearsFirstWithTheCardWhenKnown() {
        List<LookAtRowDto> rows = build(insights(List.of(mover("Food", "9400", "6300", 49.0)), "2026-09", BILLS),
                List.of(recurring(RecurringDto.QuestionState.NEEDS_ANSWER)), List.of(), Set.of(), List.of());

        assertThat(rows).extracting(LookAtRowDto::kind).containsExactly("card-statement", "new-recurring", "running-high");
        LookAtRowDto card = rows.get(0);
        assertThat(card.month()).isEqualTo("2026-09");
        assertThat(card.total()).isEqualByComparingTo("21400");
        assertThat(card.count()).isEqualTo(2);
        assertThat(card.cardName()).isEqualTo("Sample Card");
        assertThat(card.cardLast4()).isEqualTo("0001");
        assertThat(card.ackKind()).isEqualTo("CARD_STATEMENT_MISSING");
        assertThat(card.ackItemKey()).isEqualTo("2026-09");
    }

    @Test
    void cardStatementIsAbsentWhenTheServerSendsNone() {
        assertThat(build(insights(List.of(), "2026-09", null), List.of(), List.of(), Set.of(), List.of())).isEmpty();
        assertThat(build(null, List.of(), List.of(), Set.of(), List.of())).isEmpty();
    }

    @Test
    void cardStatementIsHiddenWhenMarkedGotIt() {
        assertThat(build(insights(List.of(), "2026-09", BILLS), List.of(), List.of(),
                Set.of("CARD_STATEMENT_MISSING|2026-09"), List.of())).isEmpty();
    }

    // ---- money with people ----

    @Test
    void listsTheServerPeopleRowsAfterTheCardStatementAndBeforeRepeatingPaymentsInServerOrder() {
        List<LookAtRowDto> rows = build(
                insights(List.of(), "2026-09", new InsightsDto.UnlinkedCardBills("2026-09", BigDecimal.ONE, 1, null, null)),
                List.of(recurring(RecurringDto.QuestionState.NEEDS_ANSWER)), List.of(), Set.of(),
                List.of(suggestion("SHARE_CHECK", "a"), suggestion("LENT_CHECK", "b")));

        assertThat(rows).extracting(LookAtRowDto::kind).containsExactly("card-statement", "people", "people", "new-recurring");
        assertThat(rows.stream().filter(r -> r.kind().equals("people")).map(r -> r.people().ackKey()))
                .containsExactly("a", "b");
        assertThat(rows.get(1).key()).isEqualTo("SHARE_CHECK|a");
    }

    @Test
    void hidesAPeopleRowMarkedGotItUnderItsOwnKindAndKey() {
        List<LookAtRowDto> rows = build(null, List.of(), List.of(), Set.of("SHARE_CHECK|a"),
                List.of(suggestion("SHARE_CHECK", "a"), suggestion("LENT_CHECK", "a")));
        assertThat(rows).extracting(r -> r.people().kind()).containsExactly("LENT_CHECK");
    }

    @Test
    void showsNoPeopleRowsWhenTheServerSendsNone() {
        assertThat(build(null, List.of(), List.of(), Set.of(), List.of())).isEmpty();
    }

    // ---- server-only: why and order ----

    @Test
    void whyAvailableOnlyWithTwoBaselineMonths() {
        List<LookAtRowDto> rows = LookAtService.build(insights(List.of(
                        mover("Food", "9400", "6300", 49.0),
                        mover("Fuel", "1800", "1000", 80.0)), "2026-09", null),
                List.of(), List.of(), Set.of(), List.of(), Map.of("Food", 2, "Fuel", 1));
        assertThat(rows).extracting(LookAtRowDto::category).containsExactly("Food", "Fuel");
        assertThat(rows.get(0).whyAvailable()).isTrue();
        assertThat(rows.get(1).whyAvailable()).isFalse();
    }

    @Test
    void applyOrderDropsAcknowledgedAndAppendsNew() {
        List<LookAtRowDto> rules = build(insights(List.of(
                mover("A", "2000", "1000", 100.0),
                mover("B", "9000", "3000", 200.0),
                mover("C", "5000", "2000", 150.0)), "2026-09", null), List.of(), List.of(), Set.of(), List.of());
        // Rules order is B, C, A. The stored order was made when D existed and C did not: D has since
        // been marked "Got it" (gone from the rows), and C is new.
        List<String> stored = List.of("CATEGORY_HIGH|A|2026-09", "CATEGORY_HIGH|D|2026-09", "CATEGORY_HIGH|B|2026-09");

        assertThat(LookAtService.applyOrder(rules, stored)).extracting(LookAtRowDto::category)
                .containsExactly("A", "B", "C");
        assertThat(LookAtService.applyOrder(rules, List.of())).extracting(LookAtRowDto::category)
                .containsExactly("B", "C", "A");
    }

    // ---- reading the sources ----

    /** Detection reads every one of the user's rows; "To look at" (and the AI month sheet, which
     *  builds on rowsWithAll) ran it twice per request, once more inside changedAmounts. */
    @Test
    void rowsWithAll_runsRecurringDetectionOnce_andHandsItsGroupsToChangedAmounts() {
        UUID userId = UUID.randomUUID();
        RecurringService recurringService = org.mockito.Mockito.mock(RecurringService.class);
        RecurringAnswerService answers = org.mockito.Mockito.mock(RecurringAnswerService.class);
        com.finora.people.PeopleService people = org.mockito.Mockito.mock(com.finora.people.PeopleService.class);
        InsightAcknowledgementService acks = org.mockito.Mockito.mock(InsightAcknowledgementService.class);
        List<RecurringDto> groups = List.of(recurring(RecurringDto.QuestionState.NEEDS_ANSWER));
        org.mockito.Mockito.when(recurringService.detectForUser(userId)).thenReturn(groups);
        org.mockito.Mockito.when(answers.changedAmounts(userId, groups)).thenReturn(List.of());
        org.mockito.Mockito.when(people.suggestions(userId))
                .thenReturn(new com.finora.people.PeopleSuggestions.PeopleSuggestionsResult(List.of(), List.of()));
        org.mockito.Mockito.when(acks.list(userId)).thenReturn(List.of());
        LookAtService service = new LookAtService(org.mockito.Mockito.mock(InsightsService.class), recurringService,
                answers, people, acks);

        LookAtService.Rows rows = service.rowsWithAll(userId,
                new InsightsService.Built(insights(), BASELINES, BigDecimal.ZERO, List.of(), null));

        assertThat(rows.shown()).extracting(LookAtRowDto::kind).containsExactly("new-recurring");
        org.mockito.Mockito.verify(recurringService, org.mockito.Mockito.times(1)).detectForUser(userId);
        org.mockito.Mockito.verify(answers).changedAmounts(userId, groups);
        org.mockito.Mockito.verify(answers, org.mockito.Mockito.never()).changedAmounts(userId);
    }
}
