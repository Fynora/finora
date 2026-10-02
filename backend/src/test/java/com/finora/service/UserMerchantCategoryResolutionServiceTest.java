package com.finora.service;

import com.finora.entity.*;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.LlmCompletion;
import com.finora.integrations.anthropic.LlmClient.ToolUse;
import com.finora.repository.AiAuditLogRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.UserMerchantCategoryResolutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class UserMerchantCategoryResolutionServiceTest {

    private MerchantUnderstandingService understandingService;
    private FynAvailabilityGuard availabilityGuard;
    private LlmClient llmClient;
    private AiAuditLogRepository aiAuditLogRepository;
    private UserMerchantCategoryResolutionRepository resolutionRepository;
    private CategoryRepository categoryRepository;
    private CategorizationService categorizationService;
    private com.finora.repository.UserRepository userRepository;
    private com.finora.repository.AccountRepository accountRepository;
    private com.finora.repository.TransactionRepository transactionRepository;
    private UserMerchantCategoryResolutionService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        understandingService = mock(MerchantUnderstandingService.class);
        availabilityGuard = mock(FynAvailabilityGuard.class);
        llmClient = mock(LlmClient.class);
        aiAuditLogRepository = mock(AiAuditLogRepository.class);
        resolutionRepository = mock(UserMerchantCategoryResolutionRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        categorizationService = mock(CategorizationService.class);
        userRepository = mock(com.finora.repository.UserRepository.class);
        accountRepository = mock(com.finora.repository.AccountRepository.class);
        transactionRepository = mock(com.finora.repository.TransactionRepository.class);
        service = new UserMerchantCategoryResolutionService(understandingService, availabilityGuard,
                llmClient, aiAuditLogRepository, resolutionRepository, categoryRepository, categorizationService,
                new FynNameShields(userRepository, accountRepository, transactionRepository));
        when(availabilityGuard.categorizationAvailableFor(userId)).thenReturn(true);
    }

    @Test
    void resolve_cacheHit_returnsExistingCategoryNameWithoutCallingLlm() {
        UUID categoryId = UUID.randomUUID();
        UserMerchantCategoryResolution cached = new UserMerchantCategoryResolution();
        cached.setCategoryId(categoryId);
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE))
                .thenReturn(Optional.of(cached));
        Category category = new Category();
        category.setName("Pet Care");
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));

        Optional<String> result = service.resolve(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT PET SUPPLIES PVT LTD/...");

        assertThat(result).contains("Pet Care");
        verifyNoInteractions(llmClient);
    }

    @Test
    void resolve_cacheMiss_reusesExistingCategoryTheModelNamed_createsNothing() {
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(any(), any(), any())).thenReturn(Optional.empty());
        when(understandingService.understand(any(), any(), any(), any())).thenReturn(Optional.of("A pet supplies retailer"));
        Category existing = new Category();
        existing.setUserId(userId);
        existing.setName("Pet Care");
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(existing));
        ToolUse toolUse = new ToolUse("t1", "RESOLVE_CATEGORY", Map.of("category", "Pet Care"));
        when(llmClient.complete(any())).thenReturn(new LlmCompletion(null, List.of(toolUse),
                "claude-haiku-4-5-20251001", 60, 8, "tool_use"));
        when(categorizationService.resolveOrCreateCategory(userId, "Pet Care", null)).thenReturn(existing);
        when(resolutionRepository.insertIfAbsent(any(), any(), any(), any(), any())).thenReturn(1);

        Optional<String> result = service.resolve(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT PET SUPPLIES PVT LTD/...");

        assertThat(result).contains("Pet Care");
        // Model named an EXISTING category, so no reason should be passed through as a create reason.
        verify(categorizationService).resolveOrCreateCategory(userId, "Pet Care", null);
    }

    /** Both model calls receive the narration, so both receive it redacted -- the category
     *  resolution call here, and the understanding call it makes first. */
    @Test
    void resolve_cacheMiss_bothModelCallsReceiveTheRedactedNarration() {
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(any(), any(), any())).thenReturn(Optional.empty());
        when(understandingService.understand(any(), any(), any(), any())).thenReturn(Optional.of("A pet supplies retailer"));
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of());
        when(llmClient.complete(any())).thenReturn(new LlmCompletion(null, List.of(),
                "claude-haiku-4-5-20251001", 60, 8, "end_turn"));
        String narration = "UPI-PAWS AND CLAWS STORE-pawsclawsstore@okaxis-UTIB0XXXXXX-123456789012-UPI"; // synthetic-ok

        service.resolve(userId, "vpa:pawsclawsstore", Transaction.Type.EXPENSE, narration);

        String redacted = "UPI-PAWS AND CLAWS STORE-[redacted-id]-[redacted-ifsc]-[redacted-number]-UPI";
        verify(understandingService).understand(userId, "vpa:pawsclawsstore", Transaction.Type.EXPENSE, redacted);
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(
                com.finora.integrations.anthropic.LlmClient.LlmRequest.class);
        verify(llmClient).complete(requestCaptor.capture());
        assertThat(requestCaptor.getValue().messages().get(0).content()).isEqualTo(redacted);
    }

    /** A shop QR registered under its owner's name: the name is masked before either call. */
    @Test
    void resolve_cacheMiss_sendsTheNarrationWithNamesMasked() {
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(any(), any(), any())).thenReturn(Optional.empty());
        when(understandingService.understand(any(), any(), any(), any())).thenReturn(Optional.of("A tea stall"));
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of());
        when(llmClient.complete(any())).thenReturn(new LlmCompletion(null, List.of(),
                "claude-haiku-4-5-20251001", 60, 8, "end_turn"));

        service.resolve(userId, "vpa:paytmqr12345", Transaction.Type.EXPENSE,
                "UPI-PRIYA SHARMA-paytmqr12345@paytm-UTIB0XXXXXX-123456789012-TEA STALL"); // synthetic-ok

        verify(understandingService).understand(userId, "vpa:paytmqr12345", Transaction.Type.EXPENSE,
                "UPI-[name]-[redacted-id]-[redacted-ifsc]-[redacted-number]-TEA STALL");
    }

    /** The account holder's own name, here glued into a remark, is masked from what the profile says. */
    @Test
    void resolve_cacheMiss_masksTheAccountHoldersOwnName() {
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(any(), any(), any())).thenReturn(Optional.empty());
        when(understandingService.understand(any(), any(), any(), any())).thenReturn(Optional.empty());
        User holder = new User();
        holder.setFullName("Tanvi Sharma");
        when(userRepository.findById(userId)).thenReturn(Optional.of(holder));

        service.resolve(userId, "vpa:acmefoods", Transaction.Type.EXPENSE,
                "UPI/ACME FOODS/acmefoods@okaxis/THSHARMA114");

        verify(understandingService).understand(userId, "vpa:acmefoods", Transaction.Type.EXPENSE,
                "UPI/ACME FOODS/[redacted-id]/TH[name]114");
    }

    /** A spouse's account the user imported: its holder is not the profile name, and is masked too. */
    @Test
    void resolve_cacheMiss_masksTheHolderOfEachOfTheUsersAccounts() {
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(any(), any(), any())).thenReturn(Optional.empty());
        when(understandingService.understand(any(), any(), any(), any())).thenReturn(Optional.empty());
        User profile = new User();
        profile.setFullName("Tanvi Sharma");
        when(userRepository.findById(userId)).thenReturn(Optional.of(profile));
        Account spouses = new Account();
        spouses.setAccountHolderName("ROHAN VERMA");
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(spouses));

        service.resolve(userId, "vpa:acmefoods", Transaction.Type.EXPENSE,
                "UPI/ACME FOODS/acmefoods@okaxis/ROHANVERMA22");

        verify(understandingService).understand(userId, "vpa:acmefoods", Transaction.Type.EXPENSE,
                "UPI/ACME FOODS/[redacted-id]/[name][name]22");
    }

    /** A category the user named after a person reaches the model as a token, and the token the
     *  model picks is mapped back to the real category before it is looked up. */
    @Test
    void resolve_aCategoryNamedAfterAPerson_isShieldedAndTheModelsPickIsRestored() {
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(any(), any(), any())).thenReturn(Optional.empty());
        when(understandingService.understand(any(), any(), any(), any())).thenReturn(Optional.of("A tea stall"));
        Category personal = new Category();
        personal.setUserId(userId);
        personal.setName("Priya Sharma");
        Category dining = new Category();
        dining.setUserId(userId);
        dining.setName("Dining");
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(personal, dining));
        when(transactionRepository.findPersonPaymentDescriptions(eq(userId), any()))
                .thenReturn(List.of("UPI-PRIYA SHARMA-priyasharma@okicici-UPI")); // synthetic-ok
        ToolUse toolUse = new ToolUse("t1", "RESOLVE_CATEGORY", Map.of("category", "[name-1]"));
        when(llmClient.complete(any())).thenReturn(new LlmCompletion(null, List.of(toolUse),
                "claude-haiku-4-5-20251001", 60, 8, "tool_use"));
        when(categorizationService.resolveOrCreateCategory(userId, "Priya Sharma", null)).thenReturn(personal);
        when(resolutionRepository.insertIfAbsent(any(), any(), any(), any(), any())).thenReturn(1);

        Optional<String> result = service.resolve(userId, "vpa:acmeteashop", Transaction.Type.EXPENSE,
                "UPI-ACME TEA SHOP-acmeteashop@okaxis-UPI");

        var captor = org.mockito.ArgumentCaptor.forClass(LlmClient.LlmRequest.class);
        verify(llmClient).complete(captor.capture());
        assertThat(captor.getValue().systemPrompt()).contains("Dining, [name-1]").doesNotContain("Priya");
        assertThat(result).contains("Priya Sharma");
        verify(categorizationService).resolveOrCreateCategory(userId, "Priya Sharma", null);
        // The understanding call's answer is cached for every user: no numbered token may reach it.
        verify(understandingService).understand(eq(userId), eq("vpa:acmeteashop"), eq(Transaction.Type.EXPENSE),
                org.mockito.ArgumentMatchers.argThat(text -> !text.matches("(?s).*\\[name-\\d+].*")));
    }

    /** The gate at the one entry into the model calls, not only at today's caller. */
    @Test
    void resolve_aNamedIndividualTransfer_makesNoModelCallAtAll() {
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(any(), any(), any())).thenReturn(Optional.empty());

        Optional<String> result = service.resolve(userId, "vpa:sampleuser", Transaction.Type.EXPENSE,
                "UPI-RAJESH KUMAR-sampleuser@ybl-REF881234");

        assertThat(result).isEmpty();
        verifyNoInteractions(understandingService, llmClient);
    }

    @Test
    void resolve_cacheMiss_sendsTheUsersCategoriesSortedByName() {
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(any(), any(), any())).thenReturn(Optional.empty());
        when(understandingService.understand(any(), any(), any(), any())).thenReturn(Optional.of("A pet supplies retailer"));
        Category zebra = new Category();
        zebra.setUserId(userId);
        zebra.setName("Zebra Crossing Tolls");
        Category apple = new Category();
        apple.setUserId(userId);
        apple.setName("Apple Purchases");
        // Deliberately returned out of alphabetical order -- findByUserId makes no ordering
        // guarantee, so the service itself must sort before sending (spec §4: "ordered by name").
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(zebra, apple));
        ToolUse toolUse = new ToolUse("t1", "RESOLVE_CATEGORY", Map.of("category", "Apple Purchases"));
        when(llmClient.complete(any())).thenReturn(new LlmCompletion(null, List.of(toolUse),
                "claude-haiku-4-5-20251001", 60, 8, "tool_use"));
        when(categorizationService.resolveOrCreateCategory(userId, "Apple Purchases", null)).thenReturn(apple);
        when(resolutionRepository.insertIfAbsent(any(), any(), any(), any(), any())).thenReturn(1);

        service.resolve(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT PET SUPPLIES PVT LTD/...");

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(
                com.finora.integrations.anthropic.LlmClient.LlmRequest.class);
        verify(llmClient).complete(requestCaptor.capture());
        assertThat(requestCaptor.getValue().systemPrompt())
                .contains("Apple Purchases, Zebra Crossing Tolls");
    }

    @Test
    void resolve_cacheMiss_inventsNewCategory_passesReasonAndPinsResolution() {
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(any(), any(), any())).thenReturn(Optional.empty());
        when(understandingService.understand(any(), any(), any(), any())).thenReturn(Optional.of("A pet supplies retailer"));
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of());
        ToolUse toolUse = new ToolUse("t1", "RESOLVE_CATEGORY",
                Map.of("category", "Pet Care", "reason", "Pet supplies retailer, no existing match"));
        when(llmClient.complete(any())).thenReturn(new LlmCompletion(null, List.of(toolUse),
                "claude-haiku-4-5-20251001", 60, 12, "tool_use"));
        Category created = new Category();
        UUID createdId = UUID.randomUUID();
        created.setUserId(userId);
        created.setName("Pet Care");
        when(categorizationService.resolveOrCreateCategory(userId, "Pet Care", "Pet supplies retailer, no existing match"))
                .thenAnswer(inv -> { created.setAiCreationReason(inv.getArgument(2)); return created; });
        when(resolutionRepository.insertIfAbsent(any(), any(), any(), any(), any())).thenReturn(1);

        Optional<String> result = service.resolve(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT PET SUPPLIES PVT LTD/...");

        assertThat(result).contains("Pet Care");
        verify(categorizationService).resolveOrCreateCategory(userId, "Pet Care", "Pet supplies retailer, no existing match");
        verify(resolutionRepository).insertIfAbsent(eq(userId), eq("vpa:headsupfortails"), eq("EXPENSE"), any(), any());
    }

    @Test
    void resolve_understandingUnavailable_fallsThroughWithoutCallingResolutionLlm() {
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(any(), any(), any())).thenReturn(Optional.empty());
        when(understandingService.understand(any(), any(), any(), any())).thenReturn(Optional.empty());

        Optional<String> result = service.resolve(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT PET SUPPLIES PVT LTD/...");

        assertThat(result).isEmpty();
        verifyNoInteractions(llmClient);
        verifyNoInteractions(categorizationService);
    }

    @Test
    void resolve_llmThrows_writesFailureAuditReturnsEmptyNoPlaceholderRow() {
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(any(), any(), any())).thenReturn(Optional.empty());
        when(understandingService.understand(any(), any(), any(), any())).thenReturn(Optional.of("A pet supplies retailer"));
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of());
        when(llmClient.complete(any())).thenThrow(new RuntimeException("upstream error"));

        Optional<String> result = service.resolve(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT PET SUPPLIES PVT LTD/...");

        assertThat(result).isEmpty();
        verify(aiAuditLogRepository).save(argThat(log -> log.getError() != null));
        verify(resolutionRepository, never()).insertIfAbsent(any(), any(), any(), any(), any());
    }

    @Test
    void pin_writesTheResolution() {
        UUID categoryId = UUID.randomUUID();

        service.pin(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, categoryId);

        verify(resolutionRepository).upsertPinned(eq(userId), eq("vpa:headsupfortails"), eq("EXPENSE"), eq(categoryId), any());
    }

    /**
     * Every pin() call site passes a transaction's PERSISTED counterparty_key column, which is
     * nullable and can still be null for a transaction that predates
     * Transaction#applyCounterpartyTyping -- unlike resolve()'s own caller, which always passes a
     * freshly-computed CounterpartyTyping.of(...).key() that is never null. Without this guard, a
     * null key here would violate user_merchant_category_resolution.counterparty_key's NOT NULL
     * constraint and roll back the caller's whole category-update transaction -- turning an
     * ordinary "change this old transaction's category" request into a 500.
     */
    @Test
    void pin_nullCounterpartyKey_writesNothingRatherThanViolatingNotNull() {
        service.pin(userId, null, Transaction.Type.EXPENSE, UUID.randomUUID());

        verifyNoInteractions(resolutionRepository);
    }

    /**
     * A blank (but non-null) key would satisfy the NOT NULL constraint and succeed -- but every
     * transaction with no derivable counterparty identity shares that same "" key, so one manual
     * correction on such a transaction would silently overwrite the cached resolution read by
     * every OTHER unrelated no-identity transaction for that user+direction.
     */
    @Test
    void pin_blankCounterpartyKey_writesNothing() {
        service.pin(userId, "", Transaction.Type.EXPENSE, UUID.randomUUID());

        verifyNoInteractions(resolutionRepository);
    }

    @Test
    void resolveReadOnly_cacheHit_returnsTheResolvedCategoryName() {
        UUID categoryId = UUID.randomUUID();
        UserMerchantCategoryResolution cached = new UserMerchantCategoryResolution();
        cached.setCategoryId(categoryId);
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE))
                .thenReturn(Optional.of(cached));
        Category category = new Category();
        category.setName("Pet Care");
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));

        Optional<String> result = service.resolveReadOnly(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE);

        assertThat(result).contains("Pet Care");
    }

    /**
     * Regression: staging/preview (TransactionNormalizer's suggestReadOnly path) must never
     * create real data for a transaction the user may abandon -- Bug 36's own precedent for
     * merchants, reintroduced here for AI-created categories. Before this method existed,
     * suggestReadOnly reached the SAME resolve() the confirm-time path uses, which calls the LLM
     * and persists a brand-new category + resolution row purely from generating a preview.
     */
    @Test
    void resolveReadOnly_cacheMiss_returnsEmptyWithoutCallingTheLlmOrWritingAnything() {
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(any(), any(), any())).thenReturn(Optional.empty());

        Optional<String> result = service.resolveReadOnly(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE);

        assertThat(result).isEmpty();
        verifyNoInteractions(llmClient);
        verifyNoInteractions(understandingService);
        verifyNoInteractions(categorizationService);
        verify(resolutionRepository, never()).insertIfAbsent(any(), any(), any(), any(), any());
        verify(resolutionRepository, never()).upsertPinned(any(), any(), any(), any(), any());
    }

    /**
     * Regression: ImportQueryCountIT's fixtures only ever use brand-new merchants (a cache MISS),
     * so it measures the per-row query cost of a miss but never proves a HIT through the index
     * still returns the right category -- the exact thing a user would notice going wrong (a
     * previously-taught merchant silently stops getting its category suggested in a later
     * statement's preview). Two distinct counterparties resolved to two distinct categories, to
     * catch a key/direction mixup as well as a wrong-value bug.
     */
    @Test
    void indexFor_buildsACorrectLookup_resolvingCategoryNamesInOneBatchedCall() {
        UUID petCareId = UUID.randomUUID();
        UUID diningId = UUID.randomUUID();
        UserMerchantCategoryResolution huft = new UserMerchantCategoryResolution();
        huft.setUserId(userId);
        huft.setCounterpartyKey("vpa:headsupfortails");
        huft.setDirection(Transaction.Type.EXPENSE);
        huft.setCategoryId(petCareId);
        UserMerchantCategoryResolution zepto = new UserMerchantCategoryResolution();
        zepto.setUserId(userId);
        zepto.setCounterpartyKey("vpa:zeptosample");
        zepto.setDirection(Transaction.Type.EXPENSE);
        zepto.setCategoryId(diningId);
        when(resolutionRepository.findAllByUserId(userId)).thenReturn(List.of(huft, zepto));
        Category petCare = new Category();
        petCare.setName("Pet Care");
        Category dining = new Category();
        dining.setName("Dining");
        when(categoryRepository.findAllById(argThat(ids -> {
            Set<UUID> asSet = new HashSet<>();
            ids.forEach(asSet::add);
            return asSet.equals(Set.of(petCareId, diningId));
        }))).thenReturn(List.of(withId(petCare, petCareId), withId(dining, diningId)));

        var index = service.indexFor(userId);

        assertThat(index.categoryNameFor("vpa:headsupfortails", Transaction.Type.EXPENSE)).contains("Pet Care");
        assertThat(index.categoryNameFor("vpa:zeptosample", Transaction.Type.EXPENSE)).contains("Dining");
        // Different direction, same key -- must not cross-match; the index is keyed on both.
        assertThat(index.categoryNameFor("vpa:headsupfortails", Transaction.Type.INCOME)).isEmpty();
        // Never resolved at all.
        assertThat(index.categoryNameFor("vpa:unrelated", Transaction.Type.EXPENSE)).isEmpty();
    }

    @Test
    void indexFor_noResolutionsForThisUser_returnsEmptyIndexWithoutTheBatchedCategoryLookup() {
        when(resolutionRepository.findAllByUserId(userId)).thenReturn(List.of());

        var index = service.indexFor(userId);

        assertThat(index.categoryNameFor("vpa:anything", Transaction.Type.EXPENSE)).isEmpty();
        verifyNoInteractions(categoryRepository);
    }

    /**
     * The whole point of {@link com.finora.imports.ResolutionIndex}: once built, a cache HIT must
     * come back from the in-memory index, never touch {@code resolutionRepository} again. If this
     * silently fell back to the live query on every call, ImportQueryCountIT's ceiling would still
     * measure 0.00 (the index's own two setup queries happen once, outside the measured loop) --
     * only this test actually proves the per-row lookup is gone.
     */
    @Test
    void resolveReadOnly_withIndex_cacheHit_returnsTheIndexedNameWithoutQueryingTheRepository() {
        var index = new com.finora.imports.ResolutionIndex(
                Map.of(Transaction.Type.EXPENSE, Map.of("vpa:headsupfortails", "Pet Care")));

        Optional<String> result = service.resolveReadOnly(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, index);

        assertThat(result).contains("Pet Care");
        verifyNoInteractions(resolutionRepository);
    }

    @Test
    void resolveReadOnly_withIndex_cacheMiss_returnsEmptyWithoutQueryingTheRepository() {
        var index = com.finora.imports.ResolutionIndex.empty();

        Optional<String> result = service.resolveReadOnly(userId, "vpa:brandnewvendor", Transaction.Type.EXPENSE, index);

        assertThat(result).isEmpty();
        verifyNoInteractions(resolutionRepository);
    }

    @Test
    void resolveReadOnly_withNullIndex_fallsBackToTheLiveQuery() {
        UUID categoryId = UUID.randomUUID();
        UserMerchantCategoryResolution cached = new UserMerchantCategoryResolution();
        cached.setCategoryId(categoryId);
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE))
                .thenReturn(Optional.of(cached));
        Category category = new Category();
        category.setName("Pet Care");
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));

        Optional<String> result = service.resolveReadOnly(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, null);

        assertThat(result).contains("Pet Care");
    }

    private static Category withId(Category category, UUID id) {
        org.springframework.test.util.ReflectionTestUtils.setField(category, "id", id);
        return category;
    }
}
