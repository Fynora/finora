package com.finora.service;

import com.finora.entity.AiAuditLog;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.entity.UserMerchantCategoryResolution;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.*;
import com.finora.repository.AiAuditLogRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.UserMerchantCategoryResolutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tier 2 of the AI-category-creation design (spec §3/§4) -- resolves ONE user's category for a
 * merchant, given Tier 1's (MerchantUnderstandingService) global understanding of it. Cached per
 * (user, counterparty_key, direction) forever, per spec §7/§8: an AI resolution never
 * re-triggers for the same pair once written, and a human correction re-pins it (see
 * {@link #pin}, wired in Task 7). Not @Transactional, same HikariCP-pool-exhaustion reasoning as
 * every other Fyn caller in this codebase.
 */
@Service
public class UserMerchantCategoryResolutionService {

    private static final Logger log = LoggerFactory.getLogger(UserMerchantCategoryResolutionService.class);

    private static final String PROMPT_VERSION = "user-category-resolution-v1";
    private static final String TOOL_NAME = "RESOLVE_CATEGORY";
    private static final int MAX_TOKENS = 60;
    private static final int MAX_CATEGORIES_SENT = 100;

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            You are Fyn, choosing a spending category for a bank transaction. The merchant is \
            understood to be: %s

            The user's existing categories are: %s

            Call the resolve_category tool. If one of the user's existing categories fits, reply \
            with that EXACT name and no reason. If none fit, reply with a new, short category \
            name and a brief reason why it doesn't match any existing one.
            """;

    private static final LlmTool TOOL = new LlmTool(TOOL_NAME,
            "Record the resolved category for this user and merchant.",
            Map.of("type", "object",
                    "properties", Map.of(
                            "category", Map.of("type", "string"),
                            "reason", Map.of("type", "string")),
                    "required", List.of("category")));

    private final MerchantUnderstandingService understandingService;
    private final FynAvailabilityGuard availabilityGuard;
    private final LlmClient llmClient;
    private final AiAuditLogRepository aiAuditLogRepository;
    private final UserMerchantCategoryResolutionRepository resolutionRepository;
    private final CategoryRepository categoryRepository;
    private final CategorizationService categorizationService;

    // @Lazy breaks a real circular bean dependency: CategorizationService depends on
    // FynCategorizationFallbackService (existing), which (Task 5) now depends on this service,
    // which needs CategorizationService.resolveOrCreateCategory back -- a genuine mutual
    // dependency Spring's eager constructor injection can't construct, not a design mistake to
    // engineer around by duplicating resolveOrCreateCategory's match-or-create logic here. A
    // lazy proxy defers resolving the real CategorizationService bean until resolve() actually
    // calls it at request time, well after application context startup has finished.
    public UserMerchantCategoryResolutionService(MerchantUnderstandingService understandingService,
                                                  FynAvailabilityGuard availabilityGuard, LlmClient llmClient,
                                                  AiAuditLogRepository aiAuditLogRepository,
                                                  UserMerchantCategoryResolutionRepository resolutionRepository,
                                                  CategoryRepository categoryRepository,
                                                  @Lazy CategorizationService categorizationService) {
        this.understandingService = understandingService;
        this.availabilityGuard = availabilityGuard;
        this.llmClient = llmClient;
        this.aiAuditLogRepository = aiAuditLogRepository;
        this.resolutionRepository = resolutionRepository;
        this.categoryRepository = categoryRepository;
        this.categorizationService = categorizationService;
    }

    /**
     * Staging/preview's own path (called from {@code CategorizationService.suggestReadOnly} via
     * {@code FynCategorizationFallbackService.suggestReadOnly}) -- Bug 36's own precedent
     * ({@code MerchantNormalizationEngine.resolveReadOnly}, {@code TransactionNormalizer}'s own
     * doc comment: "staging is a preview the user may abandon... same matching, same order, no
     * writes"). Returns an ALREADY-resolved category from a previously CONFIRMED transaction for
     * this exact user+merchant+direction, and nothing else -- never calls the LLM, never creates
     * a category, never writes a resolution row. A preview for a genuinely new merchant simply
     * shows no AI suggestion (falls through to whatever the rest of the waterfall picks) until
     * the user actually confirms a transaction for it.
     */
    public Optional<String> resolveReadOnly(UUID userId, String counterpartyKey, Transaction.Type direction) {
        return resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(userId, counterpartyKey, direction)
                .flatMap(cached -> categoryRepository.findById(cached.getCategoryId()).map(Category::getName));
    }

    /** As {@link #resolveReadOnly(UUID, String, Transaction.Type)}, against a {@link
     *  com.finora.imports.ResolutionIndex} the caller built once for the whole statement via
     *  {@link #indexFor} -- see that index's own doc comment for why. A null index falls back to
     *  the live query, which keeps the plain overload above working unchanged for any caller that
     *  hasn't hoisted one. */
    public Optional<String> resolveReadOnly(UUID userId, String counterpartyKey, Transaction.Type direction,
                                             com.finora.imports.ResolutionIndex resolutionIndex) {
        if (resolutionIndex != null) {
            return resolutionIndex.categoryNameFor(counterpartyKey, direction);
        }
        return resolveReadOnly(userId, counterpartyKey, direction);
    }

    /**
     * Every resolution this user has, pre-indexed for one staging pass -- see {@code
     * com.finora.imports.ResolutionIndex}'s own doc comment for why. Two queries total regardless
     * of statement size (this one, plus one batched category-name lookup below), which is what
     * makes it safe to call once per statement rather than once per row.
     */
    public com.finora.imports.ResolutionIndex indexFor(UUID userId) {
        List<UserMerchantCategoryResolution> rows = resolutionRepository.findAllByUserId(userId);
        if (rows.isEmpty()) {
            return com.finora.imports.ResolutionIndex.empty();
        }
        Map<UUID, String> namesById = categoryRepository
                .findAllById(rows.stream().map(UserMerchantCategoryResolution::getCategoryId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
        Map<Transaction.Type, Map<String, String>> byDirection = new java.util.HashMap<>();
        for (UserMerchantCategoryResolution row : rows) {
            String name = namesById.get(row.getCategoryId());
            if (name != null) {
                byDirection.computeIfAbsent(row.getDirection(), d -> new java.util.HashMap<>())
                        .put(row.getCounterpartyKey(), name);
            }
        }
        return new com.finora.imports.ResolutionIndex(byDirection);
    }

    public Optional<String> resolve(UUID userId, String counterpartyKey, Transaction.Type direction,
                                     String description) {
        Optional<String> cached = resolveReadOnly(userId, counterpartyKey, direction);
        if (cached.isPresent()) {
            return cached;
        }

        Optional<String> understanding = understandingService.understand(userId, counterpartyKey, direction, description);
        if (understanding.isEmpty()) {
            return Optional.empty();
        }
        if (!availabilityGuard.categorizationAvailableFor(userId)) {
            return Optional.empty();
        }

        // Sorted by name (spec §4) before the cap: with an unsorted source order, a user with
        // more than MAX_CATEGORIES_SENT categories would have an arbitrary, unstable subset sent
        // to the model on every call, rather than a deterministic alphabetical prefix.
        String categoryList = categoryRepository.findByUserId(userId).stream()
                .map(Category::getName)
                .sorted()
                .limit(MAX_CATEGORIES_SENT)
                .collect(Collectors.joining(", "));
        String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, understanding.get(),
                categoryList.isBlank() ? "(none yet)" : categoryList);

        LlmRequest request = LlmRequest.withTools(systemPrompt, List.of(LlmMessage.user(description)),
                MAX_TOKENS, List.of(TOOL));
        long startedAt = System.currentTimeMillis();
        LlmCompletion completion;
        try {
            completion = llmClient.complete(request);
        } catch (RuntimeException e) {
            writeAuditLog(userId, null, 0, 0, BigDecimal.ZERO,
                    (int) (System.currentTimeMillis() - startedAt), e.getMessage());
            return Optional.empty();
        }

        int latencyMs = (int) (System.currentTimeMillis() - startedAt);
        BigDecimal cost;
        String costError = null;
        try {
            cost = FynPricing.cost(completion.model(), completion.tokensIn(), completion.tokensOut());
        } catch (IllegalArgumentException e) {
            log.error("Fyn category-resolution call succeeded but has no known price for model {}", completion.model());
            cost = BigDecimal.ZERO;
            costError = "Cost unknown: " + e.getMessage();
        }
        writeAuditLog(userId, completion.model(), completion.tokensIn(), completion.tokensOut(), cost, latencyMs, costError);

        if (!completion.requestsToolUse()) {
            return Optional.empty();
        }
        Map<String, Object> input = completion.toolUses().get(0).input();
        Object rawCategory = input.get("category");
        if (!(rawCategory instanceof String categoryName) || categoryName.isBlank()) {
            return Optional.empty();
        }
        String reason = input.get("reason") instanceof String r && !r.isBlank() ? r : null;

        Category resolved = categorizationService.resolveOrCreateCategory(userId, categoryName, reason);
        resolutionRepository.insertIfAbsent(userId, counterpartyKey, direction.name(), resolved.getId(), Instant.now());
        return Optional.of(resolved.getName());
    }

    /** Human override (spec §8), called from Task 7's wiring whenever a user manually sets or
     *  corrects a transaction's category -- unconditional pin, always the latest correction, per
     *  UserMerchantCategoryResolutionRepository.upsertPinned's own doc comment.
     *
     *  <p>Every call site passes {@code t.getCounterpartyKey()} -- the PERSISTED column, which is
     *  nullable and can still be null on a transaction that predates {@code
     *  Transaction#applyCounterpartyTyping} (unlike this class's own {@code resolve()}, whose
     *  caller always passes a freshly-computed, never-null {@code CounterpartyTyping.of(...).key()}).
     *  {@code user_merchant_category_resolution.counterparty_key} is NOT NULL, so an unguarded call
     *  here would throw and roll back the caller's whole category-update transaction; a blank (but
     *  non-null) key would instead silently succeed and let one manual correction on a
     *  no-identity transaction overwrite the cached resolution for every OTHER no-identity
     *  transaction sharing that same (user, "", direction) row. Skipping both, same defensive
     *  shape as {@code SharedCorpusService.isEligible}'s own null check right before this same
     *  call site's sibling {@code recordObservation} call. */
    public void pin(UUID userId, String counterpartyKey, Transaction.Type direction, UUID categoryId) {
        if (counterpartyKey == null || counterpartyKey.isBlank()) {
            return;
        }
        resolutionRepository.upsertPinned(userId, counterpartyKey, direction.name(), categoryId, Instant.now());
    }

    private void writeAuditLog(UUID userId, String model, int tokensIn, int tokensOut,
                                BigDecimal cost, int latencyMs, String error) {
        AiAuditLog auditLog = new AiAuditLog();
        auditLog.setUserId(userId);
        auditLog.setModel(model != null ? model : "unknown");
        auditLog.setPromptVersion(PROMPT_VERSION);
        auditLog.setToolName(TOOL_NAME);
        auditLog.setTokensIn(tokensIn);
        auditLog.setTokensOut(tokensOut);
        auditLog.setCost(cost);
        auditLog.setLatencyMs(latencyMs);
        auditLog.setError(error);
        aiAuditLogRepository.save(auditLog);
        if (error != null) {
            log.warn("Fyn category-resolution call issue (user {}): {}", userId, error);
        }
    }
}
