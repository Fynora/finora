package com.finora.service;

import com.finora.config.FynProperties;
import com.finora.dto.InsightsDto;
import com.finora.dto.LookAtRowDto;
import com.finora.entity.AiInsightText;
import com.finora.entity.FeatureEntitlement;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.AiInsightTextRepository;
import com.finora.repository.AiInsightUsageRepository;
import com.finora.repository.UserRepository;
import com.finora.service.AiInsightFacts.FactSheet;
import com.finora.service.AiInsightFacts.Period;
import com.finora.service.AiInsightFacts.Row;
import com.finora.service.AiInsightFacts.Subject;
import com.finora.service.AiInsightFacts.Unit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI insights foundation §5.4, §6, §7.1: who gets AI text, single-flight, the Free allowance, the
 * regeneration limits, the stored order and feedback. The text and usage tables are in-memory fakes
 * with the repositories' own semantics (AiInsightRepositoriesIT holds the real SQL). Every name and
 * amount is invented.
 */
class AiInsightServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-20T06:00:00Z"));
    /** A clock the test moves by hand, in whatever zone it is asked for. */
    private final class MovableClock extends Clock {
        private final java.time.ZoneId zone;
        MovableClock(java.time.ZoneId zone) { this.zone = zone; }
        @Override public java.time.ZoneId getZone() { return zone; }
        @Override public Clock withZone(java.time.ZoneId z) { return new MovableClock(z); }
        @Override public Instant instant() { return now.get(); }
    }

    private final Clock clock = new MovableClock(ZoneOffset.UTC);

    private AiInsightMonthSheetBuilder monthSheets;
    private AiInsightRowSheetBuilder rowSheets;
    private LookAtService lookAtService;
    private InsightsService insightsService;
    private AiInsightGenerator generator;
    private EntitlementService entitlements;
    private FynAvailabilityGuard guard;
    private FynCostGovernanceService cost;
    private FynProperties properties;
    private User user;
    private AiInsightTextRepository texts;
    private AiInsightUsageRepository usage;
    private final Map<UUID, AiInsightText> rows = new HashMap<>();
    private final Map<String, int[]> allowance = new HashMap<>();
    private AiInsightService service;

    static final FactSheet SEPT = sheet("2026-09", "6200");
    static final FactSheet ROW_SHEET = new FactSheet("2026-09", List.of(AiInsightFacts.MONTH, new Subject("food", "Food", "Food")),
            List.of(AiInsightFacts.fact("food", "this_month", Period.THIS_MONTH, Unit.RUPEES, new BigDecimal("9400"), null),
                    AiInsightFacts.fact("food", "usual", Period.USUAL, Unit.RUPEES, new BigDecimal("6300"), null),
                    AiInsightFacts.fact("food", "change_pct", Period.THIS_MONTH, Unit.PERCENT, new BigDecimal("49"), null),
                    AiInsightFacts.fact("food", "payments_this_month", Period.THIS_MONTH, Unit.COUNT, new BigDecimal("14"), "payment"),
                    AiInsightFacts.fact("food", "payments_usual", Period.USUAL, Unit.COUNT, new BigDecimal("6"), "payment"),
                    AiInsightFacts.fact("food", "largest_1", Period.THIS_MONTH, Unit.RUPEES, new BigDecimal("2400"), null)),
            List.of(), List.of("2026-06", "2026-07", "2026-08"));

    static FactSheet sheet(String month, String food) {
        return new FactSheet(month, List.of(AiInsightFacts.MONTH, new Subject("food", "Food", "Food")),
                List.of(AiInsightFacts.fact("month", "total", Period.THIS_MONTH, Unit.RUPEES, new BigDecimal("40000"), null),
                        AiInsightFacts.fact("food", "this_month", Period.THIS_MONTH, Unit.RUPEES, new BigDecimal(food), null),
                        AiInsightFacts.fact("food", "usual", Period.USUAL, Unit.RUPEES, new BigDecimal("5000"), null)),
                List.of(new Row("row_1", "CATEGORY_HIGH|Food|" + month, "running-high", "food"),
                        new Row("row_2", "CARD_STATEMENT_MISSING|" + month, "card-statement", "month")),
                List.of("2026-06", "2026-07", "2026-08"));
    }

    private static final AiInsightTemplate STORY = new AiInsightTemplate(
            List.of(new AiInsightTemplate.Sentence("OBSERVATION", "Spending came to {month.total}.")), List.of("row_2", "row_1"));
    private static final AiInsightTemplate WHY = new AiInsightTemplate(
            List.of(new AiInsightTemplate.Sentence("EXPLANATION", "{food} rose with {food.payments_this_month}.")), List.of());

    private static LookAtRowDto runningHigh(String category, String month) {
        return LookAtRowDto.runningHigh(category, new BigDecimal("9400"), new BigDecimal("6300"), month, category + "|" + month, true);
    }

    @BeforeEach
    void setUp() {
        monthSheets = mock(AiInsightMonthSheetBuilder.class);
        rowSheets = mock(AiInsightRowSheetBuilder.class);
        lookAtService = mock(LookAtService.class);
        insightsService = mock(InsightsService.class);
        generator = mock(AiInsightGenerator.class);
        entitlements = mock(EntitlementService.class);
        guard = mock(FynAvailabilityGuard.class);
        cost = mock(FynCostGovernanceService.class);
        properties = new FynProperties();
        properties.setInsightsAudience("internal");
        UserRepository users = mock(UserRepository.class);
        user = new User();
        user.setInternalAccount(true);
        user.setTimezone("Asia/Kolkata");
        when(users.findById(userId)).thenReturn(Optional.of(user));

        when(monthSheets.build(eq(userId), any())).thenReturn(Optional.of(SEPT));
        when(rowSheets.build(userId, "2026-09", "Food")).thenReturn(Optional.of(ROW_SHEET));
        when(lookAtService.rows(eq(userId), org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(List.of(runningHigh("Food", "2026-09")));
        InsightsDto newest = new InsightsDto(List.of(), List.of(), null, null, null, "2026-09", true, null);
        when(insightsService.build(userId, null)).thenReturn(newest);
        when(insightsService.buildDetailed(eq(userId), any())).thenReturn(
                new InsightsService.Built(newest, Map.of(), new BigDecimal("40000"), List.of(), null));
        when(lookAtService.rows(eq(userId), any(InsightsService.Built.class))).thenReturn(List.of(
                runningHigh("Food", "2026-09"),
                LookAtRowDto.cardStatement(new InsightsDto.UnlinkedCardBills("2026-09", BigDecimal.ONE, 1, null, null), "2026-09")));
        when(generator.generate(eq(userId), eq("STORY"), any(), anyBoolean(), anyString()))
                .thenReturn(new AiInsightGenerator.Result(AiInsightReason.OK, STORY, "claude-haiku-4-5-20251001"));
        when(generator.generate(eq(userId), eq("WHY"), any(), anyBoolean(), anyString()))
                .thenReturn(new AiInsightGenerator.Result(AiInsightReason.OK, WHY, "claude-haiku-4-5-20251001"));
        plan("PLUS");
        when(entitlements.hasEntitlement(userId, FeatureEntitlement.FYN_INSIGHTS)).thenReturn(true);
        when(guard.insightsAvailableFor(userId)).thenReturn(true);
        when(cost.freeBudgetSpent()).thenReturn(false);
        when(cost.monthlyBudget()).thenReturn(new FynCostGovernanceService.MonthlyBudget(BigDecimal.ONE, BigDecimal.TEN,
                FynCostGovernanceService.BudgetStatus.OK));

        texts = fakeTexts();
        usage = fakeUsage();
        service = new AiInsightService(monthSheets, rowSheets, lookAtService, insightsService, generator, texts, usage,
                entitlements, guard, cost, properties, users, clock);
    }

    private void plan(String code) {
        when(entitlements.planCodeFor(userId)).thenReturn(code);
    }

    // ---- read only ----

    @Test
    void getStoryNeverCallsTheModel() {
        AiInsightService.TextView view = service.getStory(userId, null);
        assertThat(view.status()).isEqualTo("none");
        assertThat(view.canGenerate()).isTrue();
        verify(generator, never()).generate(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void generateThenReadServesTheStoredText() {
        AiInsightService.TextView made = service.generateStory(userId, null);
        assertThat(made.status()).isEqualTo("ready");
        assertThat(made.writtenByAi()).isTrue();
        assertThat(made.text()).isEqualTo("Spending came to ₹40,000.");
        assertThat(made.basis()).isEqualTo("Compared with your usual month (Jun–Aug)");

        AiInsightService.TextView read = service.getStory(userId, null);
        assertThat(read.status()).isEqualTo("ready");
        assertThat(read.id()).isEqualTo(made.id());
        assertThat(read.canGenerate()).isFalse();
        // A second generate for the same facts is the stored one, not a second call.
        assertThat(service.generateStory(userId, null).id()).isEqualTo(made.id());
        verify(generator, times(1)).generate(any(), any(), any(), anyBoolean(), any());
    }

    // ---- Free ----

    @Test
    void freeOlderMonthCannotGenerate() {
        plan("FREE");
        when(monthSheets.build(userId, "2026-07")).thenReturn(Optional.of(sheet("2026-07", "5000")));

        AiInsightService.TextView read = service.getStory(userId, "2026-07");
        AiInsightService.TextView made = service.generateStory(userId, "2026-07");

        assertThat(read.canGenerate()).isFalse();
        assertThat(made.status()).isEqualTo("none");
        assertThat(made.canGenerate()).isFalse();
        verify(generator, never()).generate(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void freeWhyCountsInTheUsersLocalMonth() {
        plan("FREE");
        now.set(Instant.parse("2026-10-31T19:00:00Z")); // 00:30 on 1 November in Asia/Kolkata

        AiInsightService.TextView view = service.why(userId, "CATEGORY_HIGH|Food|2026-09", null);

        assertThat(view.writtenByAi()).isTrue();
        verify(usage).reserveWhy(userId, "2026-11", 5);
        assertThat(view.freeLeftThisMonth()).isEqualTo(4);
    }

    @Test
    void sixthFreeWhyIsRulesWithZeroLeft() {
        plan("FREE");
        allowance.put(userId + "|2026-09", new int[]{5, 0});

        AiInsightService.TextView view = service.why(userId, "CATEGORY_HIGH|Food|2026-09", null);

        assertThat(view.writtenByAi()).isFalse();
        assertThat(view.text()).isEqualTo("14 payments this month against 6 payments in a usual month; the largest was ₹2,400.");
        assertThat(view.freeLeftThisMonth()).isZero();
        verify(generator, never()).generate(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void aFailedCheckReleasesTheReservation() {
        plan("FREE");
        when(generator.generate(eq(userId), eq("WHY"), any(), anyBoolean(), anyString()))
                .thenReturn(new AiInsightGenerator.Result(AiInsightReason.LITERAL_NUMBER, null, "claude-haiku-4-5-20251001"));

        AiInsightService.TextView view = service.why(userId, "CATEGORY_HIGH|Food|2026-09", null);

        assertThat(view.writtenByAi()).isFalse();
        assertThat(allowance.get(userId + "|2026-09")[0]).isZero();
        assertThat(rows.values()).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo("FAILED");
            assertThat(r.getReasonCode()).isEqualTo("LITERAL_NUMBER");
        });
    }

    @Test
    void cachedWhyReopenIsFree() {
        plan("FREE");
        service.why(userId, "CATEGORY_HIGH|Food|2026-09", null);
        AiInsightService.TextView again = service.why(userId, "CATEGORY_HIGH|Food|2026-09", null);

        assertThat(again.writtenByAi()).isTrue();
        verify(usage, times(1)).reserveWhy(any(), any(), anyInt());
        assertThat(again.freeLeftThisMonth()).isEqualTo(4);
    }

    @Test
    void versionBumpRegenerationDoesNotReserve() {
        plan("FREE");
        AiInsightService.TextView first = service.why(userId, "CATEGORY_HIGH|Food|2026-09", null);
        // The checker policy moves on: the stored text no longer has a current key.
        ReflectionTestUtils.setField(rows.get(first.id()), "policyVersion", "policy-v0");

        AiInsightService.TextView second = service.why(userId, "CATEGORY_HIGH|Food|2026-09", null);

        assertThat(second.id()).isNotEqualTo(first.id());
        verify(usage, times(1)).reserveWhy(any(), any(), anyInt());
        assertThat(rows.get(second.id()).isRegeneratedForVersion()).isTrue();
    }

    @Test
    void storyRewrittenAtMostOnceADay() {
        AiInsightService.TextView first = service.generateStory(userId, null);
        // A new import changes the facts an hour later.
        when(monthSheets.build(eq(userId), any())).thenReturn(Optional.of(sheet("2026-09", "7000")));
        now.set(now.get().plusSeconds(3600));

        AiInsightService.TextView later = service.generateStory(userId, null);
        assertThat(later.id()).isEqualTo(first.id());

        now.set(now.get().plusSeconds(24 * 3600));
        AiInsightService.TextView nextDay = service.generateStory(userId, null);
        assertThat(nextDay.id()).isNotEqualTo(first.id());
        verify(generator, times(2)).generate(any(), eq("STORY"), any(), anyBoolean(), any());
    }

    @Test
    void freeStoryAtMostFourAMonth() {
        plan("FREE");
        for (int i = 0; i < 6; i++) {
            when(monthSheets.build(eq(userId), any())).thenReturn(Optional.of(sheet("2026-09", String.valueOf(6000 + i))));
            service.generateStory(userId, null);
            now.set(now.get().plusSeconds(25 * 3600));
        }
        verify(generator, times(4)).generate(any(), eq("STORY"), any(), anyBoolean(), any());
    }

    @Test
    void freeBudgetSpentGivesRulesButPlusCarriesOn() {
        when(cost.freeBudgetSpent()).thenReturn(true);
        plan("FREE");
        assertThat(service.generateStory(userId, null).status()).isEqualTo("none");
        plan("PLUS");
        assertThat(service.generateStory(userId, null).status()).isEqualTo("ready");
    }

    // ---- order ----

    @Test
    void plusGetsAiOrderOnlyWhenRankingIsOn() {
        service.generateStory(userId, null);

        AiInsightService.LookAt off = service.lookAt(userId, null);
        assertThat(off.orderedByAi()).isFalse();
        assertThat(off.rows()).extracting(LookAtRowDto::kind).containsExactly("running-high", "card-statement");

        properties.setInsightsAiRanking(true);
        service.generateStory(userId, "2026-09"); // ranking now asked for; the stored one had no order request
        AiInsightService.LookAt on = service.lookAt(userId, null);
        assertThat(on.orderedByAi()).isTrue();
        assertThat(on.rows()).extracting(LookAtRowDto::kind).containsExactly("card-statement", "running-high");
    }

    @Test
    void freeNeverGetsAiOrder() {
        properties.setInsightsAiRanking(true);
        plan("FREE");
        service.generateStory(userId, null);
        verify(generator).generate(any(), eq("STORY"), any(), eq(false), any());
        assertThat(service.lookAt(userId, null).orderedByAi()).isFalse();
    }

    // ---- who gets it ----

    @Test
    void notInAudienceIsRules() {
        user.setInternalAccount(false);
        assertThat(service.generateStory(userId, null).status()).isEqualTo("none");
        assertThat(service.getStory(userId, null).canGenerate()).isFalse();
        properties.setInsightsAudience("all");
        assertThat(service.generateStory(userId, null).status()).isEqualTo("ready");
    }

    @Test
    void anUnavailableGuardIsRules() {
        when(guard.insightsAvailableFor(userId)).thenReturn(false);
        assertThat(service.generateStory(userId, null).status()).isEqualTo("none");
        verify(generator, never()).generate(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void noEntitlementIsRules() {
        when(entitlements.hasEntitlement(userId, FeatureEntitlement.FYN_INSIGHTS)).thenReturn(false);
        assertThat(service.generateStory(userId, null).status()).isEqualTo("none");
    }

    @Test
    void downgradedUserStillReadsStoredText() {
        when(monthSheets.build(userId, "2026-07")).thenReturn(Optional.of(sheet("2026-07", "5000")));
        AiInsightService.TextView made = service.generateStory(userId, "2026-07");
        plan("FREE");

        AiInsightService.TextView read = service.getStory(userId, "2026-07");

        assertThat(read.status()).isEqualTo("ready");
        assertThat(read.id()).isEqualTo(made.id());
        assertThat(read.writtenByAi()).isTrue();
    }

    // ---- why ----

    @Test
    void whyForARowTheUserDoesNotHaveIs404() {
        assertThatThrownBy(() -> service.why(userId, "CATEGORY_HIGH|Travel|2026-09", null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void plusWhyHasNoAllowance() {
        AiInsightService.TextView view = service.why(userId, "CATEGORY_HIGH|Food|2026-09", null);
        assertThat(view.writtenByAi()).isTrue();
        assertThat(view.freeLeftThisMonth()).isNull();
        verify(usage, never()).reserveWhy(any(), any(), anyInt());
    }

    @Test
    void aPendingRowFromAnotherRequestIsGenerating() {
        String hash = ROW_SHEET.hash();
        texts.insertPending(UUID.randomUUID(), userId, "WHY", "CATEGORY_HIGH|Food|2026-09", "2026-09", hash,
                AiInsightGenerator.WHY_PROMPT_VERSION, properties.getModel(), AiInsightChecker.POLICY_VERSION, "PLUS");

        assertThat(service.why(userId, "CATEGORY_HIGH|Food|2026-09", null).status()).isEqualTo("generating");
        verify(generator, never()).generate(any(), any(), any(), anyBoolean(), any());

        now.set(now.get().plusSeconds(61)); // the other request died: its row is stale
        assertThat(service.why(userId, "CATEGORY_HIGH|Food|2026-09", null).status()).isEqualTo("ready");
    }

    // ---- feedback ----

    @Test
    void feedbackIsSavedAndClearable() {
        AiInsightService.TextView made = service.generateStory(userId, null);
        service.feedback(userId, made.id(), "NOT_HELPFUL", "WRONG_NUMBER");
        assertThat(rows.get(made.id()).getFeedback()).isEqualTo("NOT_HELPFUL");
        assertThat(rows.get(made.id()).getFeedbackReason()).isEqualTo("WRONG_NUMBER");
        service.feedback(userId, made.id(), null, null);
        assertThat(rows.get(made.id()).getFeedback()).isNull();
        assertThat(rows.get(made.id()).getFeedbackReason()).isNull();
    }

    @Test
    void feedbackOnAnotherUsersTextIs404() {
        AiInsightService.TextView made = service.generateStory(userId, null);
        assertThatThrownBy(() -> service.feedback(UUID.randomUUID(), made.id(), "HELPFUL", null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void feedbackRejectsUnknownValues() {
        AiInsightService.TextView made = service.generateStory(userId, null);
        assertThatThrownBy(() -> service.feedback(userId, made.id(), "LOVED_IT", null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> service.feedback(userId, made.id(), "HELPFUL", "WRONG_NUMBER"))
                .isInstanceOf(ApiException.class);
    }

    // ---- in-memory fakes with the repositories' semantics ----

    private AiInsightText newRow(UUID id, UUID owner, String kind, String subject, String month, String hash, String pv,
                                 String model, String policy, String planCode) {
        AiInsightText t = org.springframework.beans.BeanUtils.instantiateClass(AiInsightText.class);
        ReflectionTestUtils.setField(t, "id", id);
        ReflectionTestUtils.setField(t, "userId", owner);
        ReflectionTestUtils.setField(t, "kind", kind);
        ReflectionTestUtils.setField(t, "subject", subject);
        ReflectionTestUtils.setField(t, "month", month);
        ReflectionTestUtils.setField(t, "factsHash", hash);
        ReflectionTestUtils.setField(t, "promptVersion", pv);
        ReflectionTestUtils.setField(t, "model", model);
        ReflectionTestUtils.setField(t, "policyVersion", policy);
        ReflectionTestUtils.setField(t, "planCode", planCode);
        ReflectionTestUtils.setField(t, "status", "PENDING");
        ReflectionTestUtils.setField(t, "createdAt", now.get());
        ReflectionTestUtils.setField(t, "updatedAt", now.get());
        return t;
    }

    private boolean sameKey(AiInsightText t, UUID owner, String kind, String subject, String month, String hash, String pv,
                            String model, String policy) {
        return t.getUserId().equals(owner) && t.getKind().equals(kind) && t.getSubject().equals(subject)
                && t.getMonth().equals(month) && t.getFactsHash().equals(hash) && t.getPromptVersion().equals(pv)
                && t.getModel().equals(model) && t.getPolicyVersion().equals(policy);
    }

    private AiInsightTextRepository fakeTexts() {
        AiInsightTextRepository repo = mock(AiInsightTextRepository.class);
        when(repo.insertPending(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Object[] a = inv.getArguments();
            boolean taken = rows.values().stream().anyMatch(t -> sameKey(t, (UUID) a[1], (String) a[2], (String) a[3],
                    (String) a[4], (String) a[5], (String) a[6], (String) a[7], (String) a[8]));
            if (taken) return 0;
            rows.put((UUID) a[0], newRow((UUID) a[0], (UUID) a[1], (String) a[2], (String) a[3], (String) a[4],
                    (String) a[5], (String) a[6], (String) a[7], (String) a[8], (String) a[9]));
            return 1;
        });
        when(repo.findByKey(any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Object[] a = inv.getArguments();
            return rows.values().stream().filter(t -> sameKey(t, (UUID) a[0], (String) a[1], (String) a[2], (String) a[3],
                    (String) a[4], (String) a[5], (String) a[6], (String) a[7])).findFirst();
        });
        when(repo.takeOverStale(any(), any(), any())).thenAnswer(inv -> {
            AiInsightText t = rows.get((UUID) inv.getArgument(0));
            Instant pendingBefore = inv.getArgument(1);
            Instant failedBefore = inv.getArgument(2);
            if (t == null) return 0;
            boolean stale = ("PENDING".equals(t.getStatus()) && t.getUpdatedAt().isBefore(pendingBefore))
                    || ("FAILED".equals(t.getStatus()) && t.getUpdatedAt().isBefore(failedBefore));
            if (!stale) return 0;
            t.setStatus("PENDING");
            t.setReasonCode(null);
            t.setUpdatedAt(now.get());
            return 1;
        });
        when(repo.findLatestReady(any(), any(), any(), any())).thenAnswer(inv -> rows.values().stream()
                .filter(t -> t.getUserId().equals(inv.getArgument(0)) && t.getKind().equals(inv.getArgument(1))
                        && t.getSubject().equals(inv.getArgument(2)) && t.getMonth().equals(inv.getArgument(3))
                        && "READY".equals(t.getStatus()))
                .max(java.util.Comparator.comparing(AiInsightText::getCreatedAt)));
        when(repo.countCreatedSince(any(), any(), any(), any())).thenAnswer(inv -> rows.values().stream()
                .filter(t -> t.getUserId().equals(inv.getArgument(0)) && t.getKind().equals(inv.getArgument(1))
                        && t.getMonth().equals(inv.getArgument(2))
                        && !t.getCreatedAt().isBefore(inv.getArgument(3))).count());
        when(repo.findById(any())).thenAnswer(inv -> Optional.ofNullable(rows.get((UUID) inv.getArgument(0))));
        org.mockito.Mockito.doAnswer(inv -> rows.remove((UUID) inv.getArgument(0))).when(repo).deleteById(any());
        when(repo.findLatestReadyOfVersion(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> rows.values().stream()
                .filter(t -> t.getUserId().equals(inv.getArgument(0)) && t.getKind().equals(inv.getArgument(1))
                        && t.getSubject().equals(inv.getArgument(2)) && t.getMonth().equals(inv.getArgument(3))
                        && "READY".equals(t.getStatus()) && t.getPromptVersion().equals(inv.getArgument(4))
                        && t.getPolicyVersion().equals(inv.getArgument(5)))
                .max(java.util.Comparator.comparing(AiInsightText::getCreatedAt)));
        when(repo.findByIdAndUserId(any(), any())).thenAnswer(inv -> Optional.ofNullable(rows.get((UUID) inv.getArgument(0)))
                .filter(t -> t.getUserId().equals(inv.getArgument(1))));
        when(repo.save(any())).thenAnswer(inv -> {
            AiInsightText t = inv.getArgument(0);
            rows.put(t.getId(), t);
            return t;
        });
        return repo;
    }

    private AiInsightUsageRepository fakeUsage() {
        AiInsightUsageRepository repo = mock(AiInsightUsageRepository.class);
        when(repo.reserveWhy(any(), any(), anyInt())).thenAnswer(inv -> reserve(inv.getArgument(0), inv.getArgument(1), 0, inv.getArgument(2)));
        when(repo.reserveStory(any(), any(), anyInt())).thenAnswer(inv -> reserve(inv.getArgument(0), inv.getArgument(1), 1, inv.getArgument(2)));
        when(repo.releaseWhy(any(), any())).thenAnswer(inv -> release(inv.getArgument(0), inv.getArgument(1), 0));
        when(repo.releaseStory(any(), any())).thenAnswer(inv -> release(inv.getArgument(0), inv.getArgument(1), 1));
        when(repo.whyUsed(any(), any())).thenAnswer(inv -> allowance.getOrDefault(inv.getArgument(0) + "|" + inv.getArgument(1), new int[2])[0]);
        return repo;
    }

    private int reserve(UUID owner, String month, int slot, int limit) {
        int[] counts = allowance.computeIfAbsent(owner + "|" + month, k -> new int[2]);
        if (counts[slot] >= limit) return 0;
        counts[slot]++;
        return 1;
    }

    private int release(UUID owner, String month, int slot) {
        int[] counts = allowance.computeIfAbsent(owner + "|" + month, k -> new int[2]);
        counts[slot] = Math.max(0, counts[slot] - 1);
        return 1;
    }

    @SuppressWarnings("unused")
    private List<AiInsightText> all() {
        return new ArrayList<>(rows.values());
    }
}
