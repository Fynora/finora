package com.finora.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.config.FynProperties;
import com.finora.dto.LookAtRowDto;
import com.finora.entity.AiInsightText;
import com.finora.entity.FeatureEntitlement;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.AiInsightTextRepository;
import com.finora.repository.AiInsightUsageRepository;
import com.finora.repository.UserRepository;
import com.finora.service.AiInsightFacts.FactSheet;
import com.finora.util.UserZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AI insights: the month story, "Why?" on a running-high row, and the AI order of "To look at"
 * (docs/superpowers/specs/2026-10-22-ai-insights-foundation-design.md §5.4, §6, §7.1, §8.1).
 *
 * <p>Every refusal ends in the rules text the screen already has, never an error: the app only ever
 * sees "ready" (AI or rules text), "none" (no text: keep today's month line) or "generating" (ask again
 * shortly). What decides, in order: the FYN_INSIGHTS entitlement and the rollout audience; Fyn's own
 * availability (kill switch, key, budgets); the Free plan's rules (newest month only for the story, its
 * own budget slice); the fact sheet; a stored text for exactly these facts, prompt, model and checker
 * policy; the story's once-a-day rewrite; then the single-flight claim, and only after winning it the
 * Free allowance -- so parallel taps can never spend more than the allowance, and a loser never touches
 * it. A failed model call or check gives the allowance back.
 *
 * <p>Not transactional: a model call never holds a database connection. Each repository write is its
 * own statement.
 */
@Service
public class AiInsightService {

    private static final Logger log = LoggerFactory.getLogger(AiInsightService.class);

    static final int FREE_WHY_LIMIT = 5;
    static final int FREE_STORY_LIMIT = 4;
    static final Duration PENDING_STALE = Duration.ofSeconds(60);
    static final Duration FAILED_COOLDOWN = Duration.ofMinutes(10);
    static final Duration STORY_REWRITE = Duration.ofHours(24);
    static final Set<String> UNCAPPED_PLANS = Set.of("PLUS", "PREMIUM");
    static final String STORY_SUBJECT = "MONTH";
    static final Set<String> RATINGS = Set.of("HELPFUL", "NOT_HELPFUL");
    static final Set<String> RATING_REASONS = Set.of("WRONG_NUMBER", "NOT_USEFUL", "CONFUSING");

    /** {@code status}: ready | none | generating. {@code canGenerate}: worth asking POST /story.
     *  {@code freeLeftThisMonth}: "Why?" answers a Free user has left this month; null on Plus.
     *  {@code rating}, {@code ratingReason}: the thumb this user saved on this AI text, so the app
     *  shows it again; null on rules text and when none is saved. */
    public record TextView(String status, UUID id, String text, boolean writtenByAi, String basis,
                           boolean canGenerate, Integer freeLeftThisMonth, String rating, String ratingReason) {}

    public record LookAt(List<LookAtRowDto> rows, boolean orderedByAi) {}

    private record Key(String kind, String subject, String month, String hash, String promptVersion, String model,
                       String policy) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AiInsightMonthSheetBuilder monthSheets;
    private final AiInsightRowSheetBuilder rowSheets;
    private final LookAtService lookAtService;
    private final InsightsService insightsService;
    private final AiInsightGenerator generator;
    private final AiInsightTextRepository texts;
    private final AiInsightUsageRepository usage;
    private final EntitlementService entitlements;
    private final FynAvailabilityGuard guard;
    private final FynCostGovernanceService costGovernance;
    private final FynProperties properties;
    private final UserRepository users;
    private final Clock clock;

    @Autowired
    public AiInsightService(AiInsightMonthSheetBuilder monthSheets, AiInsightRowSheetBuilder rowSheets,
                            LookAtService lookAtService, InsightsService insightsService, AiInsightGenerator generator,
                            AiInsightTextRepository texts, AiInsightUsageRepository usage, EntitlementService entitlements,
                            FynAvailabilityGuard guard, FynCostGovernanceService costGovernance, FynProperties properties,
                            UserRepository users) {
        this(monthSheets, rowSheets, lookAtService, insightsService, generator, texts, usage, entitlements, guard,
                costGovernance, properties, users, Clock.systemUTC());
    }

    AiInsightService(AiInsightMonthSheetBuilder monthSheets, AiInsightRowSheetBuilder rowSheets,
                     LookAtService lookAtService, InsightsService insightsService, AiInsightGenerator generator,
                     AiInsightTextRepository texts, AiInsightUsageRepository usage, EntitlementService entitlements,
                     FynAvailabilityGuard guard, FynCostGovernanceService costGovernance, FynProperties properties,
                     UserRepository users, Clock clock) {
        this.monthSheets = monthSheets;
        this.rowSheets = rowSheets;
        this.lookAtService = lookAtService;
        this.insightsService = insightsService;
        this.generator = generator;
        this.texts = texts;
        this.usage = usage;
        this.entitlements = entitlements;
        this.guard = guard;
        this.costGovernance = costGovernance;
        this.properties = properties;
        this.users = users;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- story

    /** Reads only: the stored story for this month's facts, if any. Never calls the model. */
    public TextView getStory(UUID userId, String month) {
        Optional<FactSheet> maybeSheet = monthSheets.build(userId, month);
        if (maybeSheet.isEmpty()) return none(false);
        FactSheet sheet = maybeSheet.get();
        String plan = planOf(userId);
        Key key = storyKey(sheet, wantsOrder(plan));
        Optional<AiInsightText> exact = texts.findByKey(userId, key.kind(), key.subject(), key.month(), key.hash(),
                key.promptVersion(), key.model(), key.policy());
        if (exact.isPresent() && AiInsightText.STATUS_READY.equals(exact.get().getStatus())) {
            return aiView(exact.get(), false, null);
        }
        if (exact.isPresent() && isFreshPending(exact.get())) return generating();
        boolean canGenerate = refusal(userId, plan) == null && freeStoryMonthAllowed(userId, plan, month, sheet);
        // A text written for earlier facts (or an earlier version) of this month still reads well
        // until a new one is written.
        Optional<AiInsightText> latest = texts.findLatestReady(userId, AiInsightText.KIND_STORY, STORY_SUBJECT, sheet.month());
        if (latest.isPresent()) return aiView(latest.get(), canGenerate, null);
        return none(canGenerate);
    }

    /** Writes the story for this month's facts if it may, else serves what there is. Idempotent: the
     *  same facts give the same stored text, and parallel calls make one model call. */
    public TextView generateStory(UUID userId, String month) {
        String plan = planOf(userId);
        AiInsightReason refused = refusal(userId, plan);
        if (refused != null) return refuse(userId, refused, none(false));
        Optional<FactSheet> maybeSheet = monthSheets.build(userId, month);
        if (maybeSheet.isEmpty()) return none(false);
        FactSheet sheet = maybeSheet.get();
        boolean free = !UNCAPPED_PLANS.contains(plan);
        if (free && !freeStoryMonthAllowed(userId, plan, month, sheet)) return none(false);
        if (free && costGovernance.freeBudgetSpent()) return refuse(userId, AiInsightReason.FREE_BUDGET_SPENT, none(false));

        boolean wantOrder = wantsOrder(plan);
        Key key = storyKey(sheet, wantOrder);
        Optional<AiInsightText> exact = findByKey(userId, key);
        if (exact.isPresent() && AiInsightText.STATUS_READY.equals(exact.get().getStatus())) {
            return aiView(exact.get(), false, null);
        }
        if (exact.isPresent() && isFreshPending(exact.get())) return generating();

        Optional<AiInsightText> latest = texts.findLatestReady(userId, AiInsightText.KIND_STORY, STORY_SUBJECT, sheet.month());
        TextView fallback = latest.map(t -> aiView(t, false, null)).orElse(none(false));
        boolean versionBump = latest.isPresent() && latest.get().getFactsHash().equals(key.hash());
        if (!versionBump && latest.isPresent()
                && texts.countCreatedSince(userId, AiInsightText.KIND_STORY, sheet.month(), now().minus(STORY_REWRITE)) > 0) {
            return fallback; // rewritten at most once a day (§6)
        }

        Optional<UUID> claimed = claim(userId, key, exact, plan);
        if (claimed.isEmpty()) {
            return exact.isPresent() && AiInsightText.STATUS_FAILED.equals(exact.get().getStatus()) ? fallback : generating();
        }
        UUID id = claimed.get();
        String allowanceMonth = null;
        if (free && !versionBump) {
            allowanceMonth = localMonth(userId);
            if (usage.reserveStory(userId, allowanceMonth, FREE_STORY_LIMIT) == 0) {
                texts.deleteById(id);
                return refuse(userId, AiInsightReason.FREE_ALLOWANCE_USED, fallback);
            }
        }
        AiInsightGenerator.Result result = generator.generate(userId, AiInsightText.KIND_STORY, sheet, wantOrder, plan);
        if (result.reason() != AiInsightReason.OK) {
            fail(id, result.reason());
            if (allowanceMonth != null) usage.releaseStory(userId, allowanceMonth);
            return fallback;
        }
        AiInsightText row = texts.findById(id).orElseThrow();
        row.setText(AiInsightRenderer.render(result.template(), sheet));
        row.setSheet(sheet.toMap());
        if (wantOrder) row.setRowOrderJson(orderKeys(result.template(), sheet));
        row.setRegeneratedForVersion(versionBump);
        row.setStatus(AiInsightText.STATUS_READY);
        row.setReasonCode(AiInsightReason.OK.name());
        row.setUpdatedAt(now());
        return aiView(texts.save(row), false, null);
    }

    // ---------------------------------------------------------------- why

    /** "Why?" on one running-high row ({@code rowKey} as GET /look-at gave it). The AI answer when it
     *  may, else the rules sentence. Idempotent per row and facts. 404 for a row this user does not have. */
    public TextView why(UUID userId, String rowKey, String month) {
        LookAtRowDto row = lookAtService.rows(userId, month).stream()
                .filter(r -> r.key().equals(rowKey)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "That row is not on your list."));
        if (!"running-high".equals(row.kind()) || !Boolean.TRUE.equals(row.whyAvailable())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "There is no explanation for that row.");
        }
        Optional<FactSheet> maybeSheet = rowSheets.build(userId, row.month(), row.category());
        if (maybeSheet.isEmpty()) return none(false);
        FactSheet sheet = maybeSheet.get();
        String plan = planOf(userId);
        boolean free = !UNCAPPED_PLANS.contains(plan);
        String allowanceMonth = localMonth(userId);
        TextView rules = rulesWhy(sheet, free ? left(userId, allowanceMonth) : null);

        Key key = new Key(AiInsightText.KIND_WHY, rowKey, sheet.month(), sheet.hash(), AiInsightGenerator.WHY_PROMPT_VERSION,
                properties.getModel(), AiInsightChecker.POLICY_VERSION);
        Optional<AiInsightText> exact = findByKey(userId, key);
        // A stored answer is the user's to reread, whatever their plan or allowance now.
        if (exact.isPresent() && AiInsightText.STATUS_READY.equals(exact.get().getStatus())) {
            return aiView(exact.get(), false, free ? left(userId, allowanceMonth) : null);
        }
        AiInsightReason refused = refusal(userId, plan);
        if (refused != null) return refuse(userId, refused, rules);
        if (free && costGovernance.freeBudgetSpent()) return refuse(userId, AiInsightReason.FREE_BUDGET_SPENT, rules);
        if (exact.isPresent() && isFreshPending(exact.get())) return generating();

        Optional<AiInsightText> latest = texts.findLatestReady(userId, AiInsightText.KIND_WHY, rowKey, sheet.month());
        boolean versionBump = latest.isPresent() && latest.get().getFactsHash().equals(key.hash());

        Optional<UUID> claimed = claim(userId, key, exact, plan);
        if (claimed.isEmpty()) {
            return exact.isPresent() && AiInsightText.STATUS_FAILED.equals(exact.get().getStatus()) ? rules : generating();
        }
        UUID id = claimed.get();
        boolean reserved = false;
        if (free && !versionBump) {
            if (usage.reserveWhy(userId, allowanceMonth, FREE_WHY_LIMIT) == 0) {
                texts.deleteById(id);
                return refuse(userId, AiInsightReason.FREE_ALLOWANCE_USED, rulesWhy(sheet, 0));
            }
            reserved = true;
        }
        AiInsightGenerator.Result result = generator.generate(userId, AiInsightText.KIND_WHY, sheet, false, plan);
        if (result.reason() != AiInsightReason.OK) {
            fail(id, result.reason());
            if (reserved) usage.releaseWhy(userId, allowanceMonth);
            return rulesWhy(sheet, free ? left(userId, allowanceMonth) : null);
        }
        AiInsightText saved = texts.findById(id).orElseThrow();
        saved.setText(AiInsightRenderer.render(result.template(), sheet));
        saved.setSheet(sheet.toMap());
        saved.setRegeneratedForVersion(versionBump);
        saved.setStatus(AiInsightText.STATUS_READY);
        saved.setReasonCode(AiInsightReason.OK.name());
        saved.setUpdatedAt(now());
        return aiView(texts.save(saved), false, free ? left(userId, allowanceMonth) : null);
    }

    // ---------------------------------------------------------------- order

    /** "To look at" for {@code month}: rules order, or for Plus and Premium with AI ranking on, the
     *  stored story's order (rows marked "Got it" since are dropped, new rows appended). Never calls
     *  the model. */
    public LookAt lookAt(UUID userId, String month) {
        InsightsService.Built built = insightsService.buildDetailed(userId, month);
        List<LookAtRowDto> rules = lookAtService.rows(userId, built);
        String plan = planOf(userId);
        String reportingMonth = built.dto().reportingMonth();
        if (!wantsOrder(plan) || reportingMonth == null) return new LookAt(rules, false);
        Optional<AiInsightText> story = texts.findLatestReadyOfVersion(userId, AiInsightText.KIND_STORY, STORY_SUBJECT,
                        reportingMonth, AiInsightGenerator.storyPromptVersion(true), AiInsightChecker.POLICY_VERSION)
                .filter(t -> t.getRowOrderJson() != null);
        if (story.isEmpty()) return new LookAt(rules, false);
        List<String> order = readOrder(story.get().getRowOrderJson());
        if (order.isEmpty()) return new LookAt(rules, false);
        return new LookAt(LookAtService.applyOrder(rules, order), true);
    }

    // ---------------------------------------------------------------- feedback

    /** Thumbs on one of the user's own AI texts: HELPFUL, NOT_HELPFUL or null (cleared); a reason only
     *  with NOT_HELPFUL, from a fixed list -- nothing a user types is stored or sent anywhere. */
    public void feedback(UUID userId, UUID textId, String rating, String reason) {
        AiInsightText row = texts.findByIdAndUserId(textId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Not found."));
        if (rating != null && !RATINGS.contains(rating)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown rating.");
        }
        if (reason != null && (!"NOT_HELPFUL".equals(rating) || !RATING_REASONS.contains(reason))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A reason goes only with a thumbs down, from the list.");
        }
        row.setFeedback(rating);
        row.setFeedbackReason(rating == null ? null : reason);
        row.setUpdatedAt(now());
        texts.save(row);
    }

    // ---------------------------------------------------------------- shared

    private String planOf(UUID userId) {
        String plan = entitlements.planCodeFor(userId);
        return plan != null && UNCAPPED_PLANS.contains(plan) ? plan : "FREE";
    }

    private boolean wantsOrder(String plan) {
        return UNCAPPED_PLANS.contains(plan) && properties.isInsightsAiRanking();
    }

    private Key storyKey(FactSheet sheet, boolean wantOrder) {
        return new Key(AiInsightText.KIND_STORY, STORY_SUBJECT, sheet.month(), sheet.hash(),
                AiInsightGenerator.storyPromptVersion(wantOrder), properties.getModel(), AiInsightChecker.POLICY_VERSION);
    }

    /** Null when this user may have AI text now; else why not. Plan rules beyond entitlement are the caller's. */
    private AiInsightReason refusal(UUID userId, String plan) {
        if (!entitlements.hasEntitlement(userId, FeatureEntitlement.FYN_INSIGHTS)) return AiInsightReason.NOT_IN_AUDIENCE;
        if (!inAudience(userId)) return AiInsightReason.NOT_IN_AUDIENCE;
        if (!guard.insightsAvailableFor(userId)) {
            if (costGovernance.monthlyBudget().status() == FynCostGovernanceService.BudgetStatus.STOPPED) {
                return AiInsightReason.ORG_BUDGET_SPENT;
            }
            if (costGovernance.userDailyCapReached(userId)) return AiInsightReason.USER_DAILY_CAP;
            return AiInsightReason.PROVIDER_ERROR;
        }
        return null;
    }

    private boolean inAudience(UUID userId) {
        if ("all".equalsIgnoreCase(properties.getInsightsAudience())) return true;
        return users.findById(userId).map(User::isInternalAccount).orElse(false);
    }

    /** Free gets the story for the newest reporting month only (§6). */
    private boolean freeStoryMonthAllowed(UUID userId, String plan, String requestedMonth, FactSheet sheet) {
        if (UNCAPPED_PLANS.contains(plan) || requestedMonth == null) return true;
        String newest = insightsService.build(userId, null).reportingMonth();
        return sheet.month().equals(newest);
    }

    private Optional<AiInsightText> findByKey(UUID userId, Key k) {
        return texts.findByKey(userId, k.kind(), k.subject(), k.month(), k.hash(), k.promptVersion(), k.model(), k.policy());
    }

    /** The single-flight claim: a new PENDING row, or a stale PENDING / cooled-down FAILED row taken
     *  over. Empty when another request holds it (or a recent failure's cooldown holds). */
    private Optional<UUID> claim(UUID userId, Key k, Optional<AiInsightText> existing, String plan) {
        if (existing.isEmpty()) {
            UUID id = UUID.randomUUID();
            int won = texts.insertPending(id, userId, k.kind(), k.subject(), k.month(), k.hash(), k.promptVersion(),
                    k.model(), k.policy(), plan);
            return won == 1 ? Optional.of(id) : Optional.empty();
        }
        Instant now = now();
        int taken = texts.takeOverStale(existing.get().getId(), now.minus(PENDING_STALE), now.minus(FAILED_COOLDOWN));
        return taken == 1 ? Optional.of(existing.get().getId()) : Optional.empty();
    }

    private void fail(UUID id, AiInsightReason reason) {
        texts.findById(id).ifPresent(row -> {
            row.setStatus(AiInsightText.STATUS_FAILED);
            row.setReasonCode(reason.name());
            row.setUpdatedAt(now());
            texts.save(row);
        });
    }

    private boolean isFreshPending(AiInsightText t) {
        return AiInsightText.STATUS_PENDING.equals(t.getStatus()) && t.getUpdatedAt().isAfter(now().minus(PENDING_STALE));
    }

    private String localMonth(UUID userId) {
        return YearMonth.now(clock.withZone(UserZone.forUser(users, userId))).toString();
    }

    private int left(UUID userId, String allowanceMonth) {
        return Math.max(0, FREE_WHY_LIMIT - usage.whyUsed(userId, allowanceMonth));
    }

    private Instant now() {
        return clock.instant();
    }

    private TextView aiView(AiInsightText t, boolean canGenerate, Integer freeLeft) {
        String basis = t.getSheet() == null ? null : AiInsightRenderer.basisFor(FactSheet.fromMap(t.getSheet()));
        return new TextView("ready", t.getId(), t.getText(), true, basis, canGenerate, freeLeft, t.getFeedback(),
                t.getFeedbackReason());
    }

    private static TextView rulesWhy(FactSheet sheet, Integer freeLeft) {
        return new TextView("ready", null, AiInsightRenderer.whyRulesText(sheet), false, AiInsightRenderer.basisFor(sheet),
                false, freeLeft, null, null);
    }

    private static TextView none(boolean canGenerate) {
        return new TextView("none", null, null, false, null, canGenerate, null, null, null);
    }

    private static TextView generating() {
        return new TextView("generating", null, null, false, null, false, null, null, null);
    }

    private static TextView refuse(UUID userId, AiInsightReason reason, TextView view) {
        log.info("AI insight for user {} not written: {}", userId, reason);
        return view;
    }

    private static String orderKeys(AiInsightTemplate template, FactSheet sheet) {
        Map<String, String> keyById = sheet.rows().stream()
                .collect(Collectors.toMap(AiInsightFacts.Row::id, AiInsightFacts.Row::key));
        List<String> keys = template.order().stream().map(keyById::get).filter(Objects::nonNull).toList();
        try {
            return JSON.writeValueAsString(keys);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store an AI insight order", e);
        }
    }

    private static List<String> readOrder(String json) {
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
