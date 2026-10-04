package com.finora.transactions;

import com.finora.dto.PagedResponse;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.repository.AccountRepository;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.OwnershipGuard;
import com.finora.service.AuditService;
import com.finora.service.BankManagementService;
import com.finora.service.CategorizationService;
import com.finora.service.ReconciliationService;
import com.finora.service.RecurringService;
import com.finora.service.SmsProvider;
import com.finora.service.SmsResult;
import com.finora.service.TransactionGroupingService;
import com.finora.util.CategoryRules;
import com.finora.util.CounterpartyType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final AccountRepository accountRepository;
    private final StatementImportRepository statementImportRepository;
    private final com.finora.accounts.RowBalanceEffect rowBalanceEffect;
    private final com.finora.accounts.BalanceCoverage balanceCoverage;
    private final CategorizationService categorizationService;
    private final ReconciliationService reconciliationService;
    private final RecurringService recurringService;
    private final AuditService auditService;
    private final AuditLogRepository auditLogRepository;
    private final BankManagementService bankManagementService;
    private final UserRepository userRepository;
    private final SmsProvider smsProvider;
    private final TransactionGroupingService transactionGroupingService;
    private final com.finora.observability.ReconciliationMetrics reconciliationMetrics;
    private final com.finora.service.TransactionGraphService transactionGraphService;
    private final com.finora.service.SharedCorpusService sharedCorpusService;
    private final com.finora.service.UserMerchantCategoryResolutionService userMerchantCategoryResolutionService;
    private final com.finora.observability.QuickSortMetrics quickSortMetrics;

    public TransactionService(TransactionRepository transactionRepository, CategoryRepository categoryRepository,
                               AccountRepository accountRepository,
                               StatementImportRepository statementImportRepository,
                               CategorizationService categorizationService,
                               ReconciliationService reconciliationService,
                               RecurringService recurringService,
                               AuditService auditService,
                               AuditLogRepository auditLogRepository,
                               BankManagementService bankManagementService,
                               UserRepository userRepository,
                               SmsProvider smsProvider,
                               TransactionGroupingService transactionGroupingService,
                               com.finora.observability.ReconciliationMetrics reconciliationMetrics,
                               com.finora.service.TransactionGraphService transactionGraphService,
                               com.finora.service.SharedCorpusService sharedCorpusService,
                               com.finora.service.UserMerchantCategoryResolutionService userMerchantCategoryResolutionService,
                               com.finora.observability.QuickSortMetrics quickSortMetrics) {
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.accountRepository = accountRepository;
        this.statementImportRepository = statementImportRepository;
        this.rowBalanceEffect = new com.finora.accounts.RowBalanceEffect(statementImportRepository);
        this.balanceCoverage = new com.finora.accounts.BalanceCoverage(statementImportRepository, transactionRepository);
        this.categorizationService = categorizationService;
        this.reconciliationService = reconciliationService;
        this.recurringService = recurringService;
        this.auditService = auditService;
        this.auditLogRepository = auditLogRepository;
        this.bankManagementService = bankManagementService;
        this.userRepository = userRepository;
        this.smsProvider = smsProvider;
        this.transactionGroupingService = transactionGroupingService;
        this.reconciliationMetrics = reconciliationMetrics;
        this.transactionGraphService = transactionGraphService;
        this.sharedCorpusService = sharedCorpusService;
        this.userMerchantCategoryResolutionService = userMerchantCategoryResolutionService;
        this.quickSortMetrics = quickSortMetrics;
    }

    // Never a real bank id (BankRegistry ids are short uppercase codes like "PNB"/"OTHER") --
    // used as a stand-in for "no bank matched this search keyword" so the repository's
    // `a.bankId IN :bankIds` always binds a real, non-empty collection rather than an empty one,
    // sidestepping any JPA-provider-specific edge cases around empty IN(...) lists entirely.
    private static final String NO_BANK_MATCH_SENTINEL = "__NO_BANK_MATCH__";

    // Same reasoning as NO_BANK_MATCH_SENTINEL just above, for the category-name half of the same
    // search: a nil UUID a real Category row can never carry (categoryRepository.save relies on
    // the entity's @GeneratedValue, never this all-zero literal), so `t.categoryId IN :categoryIds`
    // always binds a real, non-empty collection.
    private static final UUID NO_CATEGORY_MATCH_SENTINEL = UUID.fromString("00000000-0000-0000-0000-000000000000");

    @Transactional(readOnly = true)
    // Bug fix: the repository call below already returns a Spring Data Page<Transaction>, which
    // computes totalElements/totalPages as part of the same query -- this used to throw that
    // metadata away and hand back a bare List, leaving the frontend with no way to know whether a
    // next page existed (see PagedResponse's own doc comment; the admin Users directory hit this
    // exact gap first and got a real fix, this endpoint didn't). Now returns the same envelope.
    public PagedResponse<TransactionDto> search(UUID userId, TransactionDto.FilterRequest f) {
        // BH-009: sortDir went into Sort.Direction.fromString unvalidated, so ?sortDir=bogus threw
        // IllegalArgumentException and 500'd -- in the same method whose own comment two blocks
        // down explains that page and size are clamped precisely so a malformed param stops doing
        // that. Two of the three inputs were fixed and the third was missed.
        //
        // fromOptionalString, so an unrecognised value falls back to the default rather than
        // failing the search. That matches how sortField already behaves (mapSortField's `default`
        // arm quietly yields txnDate) -- a sort direction is a presentation preference, and
        // refusing to return a user's transactions over one would be a worse answer than sorting
        // them the usual way.
        Sort sort = Sort.by(
                Sort.Direction.fromOptionalString(f.sortDir() == null ? "" : f.sortDir())
                        .orElse(Sort.Direction.DESC),
                f.sortField() == null ? "txnDate" : mapSortField(f.sortField()));
        // Bank-aware search (PRD's "Improve Search"): a keyword like "Punjab National" should
        // also match transactions on accounts held with that bank, not just description/merchant
        // text. bankManagementService.search() covers both the built-in registry and admin-added
        // custom banks (V26__custom_banks.sql) -- resolved here, one layer above the repository,
        // since neither is a database table the query could join against directly.
        List<String> matchingBankIds = f.keyword() != null && !f.keyword().isBlank()
                ? bankManagementService.search(f.keyword()).stream().map(com.finora.accounts.AccountDto.BankDto::id).toList()
                : List.of();
        List<String> bankIdsParam = matchingBankIds.isEmpty() ? List.of(NO_BANK_MATCH_SENTINEL) : matchingBankIds;
        // Same "Improve Search" gap, the category half: a keyword like "Groceries" is how someone
        // actually thinks of a transaction they're looking for, and this search bar sits directly
        // above a table with a Category column -- but category, like bank, has no column on
        // Transaction itself (categoryId is a plain UUID reference, not a JPQL-joinable path from
        // this query the way description/merchant already are), so it needs the identical
        // resolve-ids-first treatment. categoryRepository.findByUserId is already an in-memory
        // list this small (a user's own category set, not the whole table) -- same cost class as
        // BankRegistry's own linear scan above.
        List<UUID> matchingCategoryIds = f.keyword() != null && !f.keyword().isBlank()
                ? categoryRepository.findByUserId(userId).stream()
                        .filter(c -> c.getName() != null
                                && c.getName().toLowerCase(java.util.Locale.ROOT)
                                        .contains(f.keyword().trim().toLowerCase(java.util.Locale.ROOT)))
                        .map(Category::getId)
                        .toList()
                : List.of();
        List<UUID> categoryIdsParam = matchingCategoryIds.isEmpty()
                ? List.of(NO_CATEGORY_MATCH_SENTINEL) : matchingCategoryIds;
        // Bug fix: an unclamped negative page or oversized size reached PageRequest.of directly,
        // which throws IllegalArgumentException -- unhandled in GlobalExceptionHandler, so the
        // Ledger's own search endpoint 500'd on a malformed page param instead of just clamping it
        // the way every other paginated endpoint in this codebase does (see PageBounds).
        int safeSize = com.finora.util.PageBounds.safeSize(f.size() > 0 ? f.size() : 20);
        int safePage = com.finora.util.PageBounds.safePage(f.page());
        // Deleted-account leak (see DashboardService.summarize for the original fix): when the
        // caller didn't ask for one specific account, the "all accounts" search must still exclude
        // a deleted account's transactions, which deliberately keep deleted_at unset -- see the
        // repository query's own doc comment for why this is only consulted when f.accountId() is
        // null (an explicit accountId is trusted as-is, unchanged from before).
        List<UUID> liveAccountIds = accountRepository.findByUserId(userId).stream()
                .map(Account::getId).toList();
        var page = transactionRepository.search(
                userId, f.accountId(), f.categoryId(),
                com.finora.util.EnumParsing.parseIfPresent(Transaction.Type.class, f.type(), "type"),
                com.finora.util.EnumParsing.parseIfPresent(Transaction.ReconciliationStatus.class, f.status(), "status"),
                f.dateFrom(), f.dateTo(), f.amountMin(), f.amountMax(),
                // Escaped for LIKE (see LikePatterns) -- transaction descriptions are full of
                // literal percent signs ("2.5% CASHBACK"), and an unescaped one turned an exact
                // search into a prefix search silently. Only the repository term is escaped:
                // bankManagementService.search() above matches in memory with contains().
                com.finora.util.LikePatterns.escape(f.keyword()), bankIdsParam, categoryIdsParam, liveAccountIds,
                f.international(),
                PageRequest.of(safePage, safeSize, sort)
        );
        Map<UUID, String> namesById = categoryNamesById(userId);
        return PagedResponse.of(page.map(t -> TransactionDto.from(t, namesById.getOrDefault(t.getCategoryId(), "Uncategorized"))));
    }

    private Map<UUID, String> categoryNamesById(UUID userId) {
        return categoryRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
    }

    private String mapSortField(String field) {
        return switch (field) {
            case "date" -> "txnDate";
            case "amount" -> "amount";
            default -> "txnDate";
        };
    }

    /**
     * How much a transaction of this type/amount moves its OWN account's running balance.
     * Savings/Wallet/Investment accounts follow the plain ledger convention (income adds,
     * expense subtracts). Credit cards are inverted: Account.balance represents money OWED, not
     * cash on hand — see DashboardService.computeHealthScore's debt-utilization math, which
     * divides balance by creditLimit and expects both to be positive magnitudes — so a purchase
     * (EXPENSE) increases what's owed and a payment/credit (INCOME) reduces it.
     */
    private BigDecimal balanceDelta(Account account, Transaction.Type type, BigDecimal amount) {
        // Delegates to AccountBalanceConvention, which now owns this rule. It used to live here as
        // a private method, which is precisely why ImportService could not reuse it and shipped
        // Bug 17 -- a confirmed statement inserted its rows and never moved the balance. Same
        // arithmetic, one owner.
        return com.finora.accounts.AccountBalanceConvention.balanceDelta(
                account.getAccountType(), type, amount);
    }

    private void adjustAccountBalance(UUID accountId, BigDecimal delta) {
        if (delta.compareTo(BigDecimal.ZERO) == 0) return;
        accountRepository.findById(accountId).ifPresent(account -> {
            account.setBalance(account.getBalance().add(delta));
            accountRepository.save(account);
        });
        // If the account was itself deleted out from under this transaction, there's nothing to
        // adjust — findById is filtered by Account's own @SQLRestriction, same as everywhere
        // else in the app treats a since-deleted account as no longer live.
    }

    /**
     * SEC-06 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Checked first, and
     * deliberately a plain findByUserIdAndIdempotencyKey rather than an insert-and-catch: the
     * common case (no key, or a genuinely first attempt) must not pay for an exception path, and
     * this mirrors the "check first, let the database catch the race" shape ImportJobService.accept
     * and ImportSessionService.parseAndStageWithSession already use for the equivalent import-side
     * check (see V74/V97's own migration comments). A concurrent duplicate that slips past this
     * read loses the race at the unique index (V97) instead, and gets GlobalExceptionHandler's
     * existing 409 for DataIntegrityViolationException -- the same outcome the import-side callers
     * already accept, and a retry of that same request lands on this early-return branch instead.
     *
     * <p>The lookup itself sees soft-deleted rows on purpose (see the repository method's own doc
     * comment), and a match found there is only ever returned once {@link #requireSameRequest} has
     * confirmed the replay's fields agree with what was recorded the first time -- a key match
     * alone is not sufficient, see that method's doc comment.
     */
    @Transactional
    public TransactionDto create(UUID userId, TransactionDto.CreateRequest req) {
        // Blank normalized to null up front, and reused below for both the check and the persisted
        // value -- an empty string is not a real key (V97's index would otherwise start treating
        // "every caller that sends an empty string" as one colliding identity), and a caller that
        // omits the field entirely already sends null, so both spellings of "no key" must behave
        // identically rather than one of them silently opting into idempotency by accident.
        String idempotencyKey = (req.idempotencyKey() == null || req.idempotencyKey().isBlank())
                ? null : req.idempotencyKey();
        if (idempotencyKey != null) {
            Transaction existing = transactionRepository
                    .findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                    .orElse(null);
            if (existing != null) {
                String categoryName = categoryRepository.findById(existing.getCategoryId())
                        .map(Category::getName).orElse("Uncategorized");
                requireSameRequest(existing, req, categoryName);
                return TransactionDto.from(existing, categoryName);
            }
        }

        // Bug fix: req.accountId() used to go straight onto the transaction with no check that it
        // actually belongs to userId -- every other entry point into an Account (AccountService's
        // own getOwned, and this class's own getOwned for transactions) verifies ownership before
        // acting; this was the one place that didn't. Without it, any authenticated user could
        // POST here with another user's accountId and both plant a transaction pointed at it AND
        // silently move that victim's real account balance via adjustAccountBalance() below.
        Account account = getOwnedAccount(userId, req.accountId());
        // Credit card transactions always arrive via statement import, so manual entry only ever
        // produces redundant rows for that account type -- the frontend already hides this option
        // (AddTransactionModal.tsx / AddTransactionSheet.tsx filter CREDIT_CARD out of the account
        // picker), but this endpoint has no other caller to trust that, so the block belongs here
        // too. SAVINGS/WALLET/INVESTMENT are untouched -- they have no import path that covers
        // every possible movement (e.g. handing someone cash), so manual entry stays available.
        if (account.getAccountType() == Account.Type.CREDIT_CARD) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Manual transactions aren't supported on credit card accounts -- they arrive via statement import.");
        }

        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(account.getId());
        t.setTxnDate(req.date());
        t.setDescription(req.description());
        // Who was on the other side -- a separate question from what the money was for, and one
        // the narration answers far more often (79.2% of the real corpus, against ~47% for
        // category). Deliberately ABOVE and outside the category decision further down: this is
        // derived from the narration alone and is equally true whether the category came from the
        // engine or from the user typing one in, and the manual branch is the one a careful user
        // exercises most. Shares one derivation with the import path and the backfill sweep --
        // see CounterpartyTyping; CounterpartyWiringTest pins that agreement rather than trusting it.
        t.applyCounterpartyTyping(req.description());
        requireAmountWithinBounds(req.amount());
        t.setAmount(req.amount());
        t.setTxnType(com.finora.util.EnumParsing.parse(Transaction.Type.class, req.type(), "type"));
        t.setMerchant(CategoryRules.extractMerchantLabel(req.description(), t.getTxnType()));
        t.setTags(req.tags());
        t.setSource(Transaction.Source.MANUAL);
        t.setIdempotencyKey(idempotencyKey);

        String categoryName = req.categoryName();
        Category category;
        CategorizationService.Suggestion suggestion = null;
        if (categoryName != null) {
            // An explicit category from the caller is a real decision — resolve it, learn from
            // it, and mark it manually set so the UI never shows it as an engine guess.
            t.setMerchantId(categorizationService.resolveMerchantId(userId, req.description()));
            category = categorizationService.resolveOrCreateCategory(userId, categoryName);
            categorizationService.learn(userId, req.description(), category.getId());
            sharedCorpusService.recordObservation(userId, t.getCounterpartyKey(), t.getCounterpartyType(),
                    t.getTxnType(), category.getName());
            userMerchantCategoryResolutionService.pin(userId, t.getCounterpartyKey(), t.getTxnType(), category.getId());
            t.setCategoryManuallySet(true);
            t.setDecisionSource(Transaction.DecisionSource.MANUAL);
        } else {
            // No explicit category given — ask the engine. A "default" (no rule/learned match)
            // suggestion isn't a real decision, so file it under Other but flag it for the
            // "Ask Once" review queue instead of silently learning a non-decision -- unless the
            // user's own auto-apply confidence threshold says otherwise; see
            // CategorizationService.needsCategoryReview's own doc comment.
            suggestion = categorizationService.suggest(userId, req.description(), req.amount(), null, t.getTxnType());
            t.setMerchantId(suggestion.merchantId()); // already resolved as part of suggest() — no need to resolve twice
            category = categorizationService.resolveOrCreateCategory(userId, suggestion.category());
            t.setNeedsCategoryReview(categorizationService.needsCategoryReview(
                    userId,
                    CategorizationService.isUnconfirmedGuess(suggestion.source(), suggestion.category()),
                    suggestion.confidence()));
            t.setDecisionSource(suggestion.decisionSource());
            t.setDecisionRuleId(suggestion.ruleId());
            t.setDecisionConfidence(suggestion.confidence());
        }
        t.setCategoryId(category.getId());
        // MARK_TRANSFER/MARK_INVESTMENT/ADD_TAG rules -- see CategorizationService.applySideEffectRules's
        // doc comment for why this runs after category/amount/merchant/txnType are all set, and
        // why it's safe (and intended) to override the category just assigned above. A
        // MARK_INVESTMENT match returns the new Category -- reassigning `category` here keeps
        // the response below in sync with what actually got persisted.
        Category sideEffectCategory = categorizationService.applySideEffectRules(userId, t);
        if (sideEffectCategory != null) {
            category = sideEffectCategory;
            // Bug fix: reassigning `category` alone didn't actually keep `t` in sync with it --
            // t.setCategoryId() above already ran against the PRE-override category, and nothing
            // called it again. The response (built from `category`) looked right; the persisted
            // transaction (built from `t`) silently kept the wrong one. Same bug, same fix, as
            // CsvImportService.confirm()'s equivalent side-effect-rule override -- see that
            // method's own comment on this exact pattern.
            t.setCategoryId(category.getId());
        }
        // create() is always a real write (unlike CsvImportService, there's no staging/preview
        // step in between) -- safe to record the suggestion's rule match here. Only once side
        // effects have run, and only if that rule is still the decision: a MARK_INVESTMENT rule
        // that replaced the category is the decision now (applySideEffectRules counts that one),
        // and the rule it replaced did not decide what was stored.
        if (suggestion != null && java.util.Objects.equals(t.getDecisionRuleId(), suggestion.ruleId())) {
            categorizationService.recordRuleMatch(suggestion.ruleId());
        }

        // A transaction dated on or before the day the balance is already known as of is inside that
        // figure -- the bank's closing balance for that period, or the balance the user typed in
        // (which is what they had then, this transaction included). Adding it counted it twice. It
        // is recorded on the row so every later edit or delete knows its effect is not in the
        // balance (AccountBalanceConvention.manualRowInsideStatedFigure).
        java.time.LocalDate knownThrough = balanceCoverage.knownThrough(account);
        boolean insideStatedFigure = knownThrough != null && t.getTxnDate() != null
                && !t.getTxnDate().isAfter(knownThrough);
        if (insideStatedFigure) t.setBalanceCoveredThrough(knownThrough);

        Transaction saved = transactionRepository.save(t);
        if (!insideStatedFigure) adjustAccountBalance(saved.getAccountId(), balanceOf(saved));
        // Both re-run synchronously, right after persistence, on every write path that can
        // change a user's transaction set -- same treatment for both detection engines now (see
        // docs/team-message-financial-intelligence-v1-closeout.md). Recurring detection can't
        // run before persistence the way an earlier draft pipeline diagram suggested: it works
        // by re-reading the user's transactions from the DB and grouping them, so it needs this
        // row (and its siblings) already saved, same precondition reconciliation already has.
        reconciliationService.reconcileForUser(userId);
        recurringService.detectForUser(userId);
        auditService.record(userId, "TRANSACTION_CREATED", "Transaction", saved.getId(),
                Map.of("amount", saved.getAmount(), "type", saved.getTxnType().name(), "source", saved.getSource().name()));
        sendTransactionAlert(userId, saved);
        return TransactionDto.from(saved, category.getName());
    }

    /**
     * Bug fix (gap review of SEC-06): the replay check above used to return {@code existing}
     * unconditionally the moment the key matched, with no check that the rest of the request
     * actually matches what was recorded under that key the first time. An idempotency key
     * identifies one logical request -- replaying it is only correct when the request really is a
     * replay of that same request. A client bug that resent the same key with a different amount
     * or account would otherwise silently get back the stale original instead of a rejection.
     *
     * <p>Compared against the fields a genuine retry actually resends -- account, amount, type,
     * date, description, and (only when the caller supplies one) category. Not tags/notes: those
     * are free-form annotations whose presence doesn't change which financial event this is.
     */
    private void requireSameRequest(Transaction existing, TransactionDto.CreateRequest req, String existingCategoryName) {
        boolean matches = existing.getAccountId().equals(req.accountId())
                && req.amount() != null && existing.getAmount().compareTo(req.amount()) == 0
                && existing.getTxnType().name().equalsIgnoreCase(req.type())
                && java.util.Objects.equals(existing.getTxnDate(), req.date())
                && java.util.Objects.equals(existing.getDescription(), req.description())
                && (req.categoryName() == null || req.categoryName().equals(existingCategoryName));
        if (!matches) {
            throw new ApiException(ErrorCode.TXN_IDEMPOTENCY_KEY_REUSED);
        }
    }

    /** Real-time transaction alert SMS -- scoped deliberately to this one manual-entry path, not
     *  bulk statement import (CsvImportService/PdfImportService never call create(), so a 200-row
     *  statement import never fires 200 SMS). Requires a verified phone number, same trust bar as
     *  every other phone-number-dependent feature (see PhoneVerificationProvider) -- an
     *  unverified number is exactly the number a stranger could have mistyped at registration.
     *
     *  Bug fix: this used to run synchronously inside create()'s own @Transactional method, which
     *  meant a slow (or hanging) 2Factor API call held the DB connection for create()'s entire
     *  transaction -- and, worse, could send an alert for a transaction whose surrounding
     *  transaction later rolled back for an unrelated reason. Deferred to run only after the
     *  transaction actually commits, via TransactionSynchronizationManager -- falls back to
     *  sending immediately when no transaction synchronization is active (e.g. a unit test calling
     *  create() directly against a plain object, with no real Spring transaction in play). */
    private void sendTransactionAlert(UUID userId, Transaction t) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            doSendTransactionAlert(userId, t);
                        }
                    });
        } else {
            doSendTransactionAlert(userId, t);
        }
    }

    private void doSendTransactionAlert(UUID userId, Transaction t) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || !user.isPhoneVerified() || user.getPhoneNumber() == null) return;
        SmsResult result = smsProvider.sendTransactionAlert(
                user.getPhoneNumber(), t.getDescription(), t.getAmount(), t.getTxnType().name());
        // recordEvenOnRollback, not record: this runs inside afterCommit(), on the thread that
        // just committed create()'s transaction -- see AuthService.register()'s welcome-email
        // block and AuditService#recordEvenOnRollback's own doc comment for why a plain record()
        // silently loses the write there.
        auditService.recordEvenOnRollback(userId, "SMS_SENT", "User", userId, Map.of(
                "type", "transaction_alert", "provider", result.provider().name(), "success", result.success()));
    }

    private BigDecimal balanceOf(Transaction t) {
        Account account = accountRepository.findById(t.getAccountId()).orElse(null);
        return account == null ? BigDecimal.ZERO : balanceDelta(account, t.getTxnType(), t.getAmount());
    }

    /**
     * Where this row's own net effect sits right now -- see {@link com.finora.accounts.RowBalanceEffect}
     * for the rule. Editing or deleting a row moves only that place.
     *
     * <p>Not in the balance at all: a row reconciliation marked DUPLICATE and took back off when it
     * wrote the mark (BH-003, {@code ReconciliationService.reverseBalanceContribution}; recorded as
     * {@link Transaction#isDuplicateBalanceReversed()}) -- {@link #confirmNotDuplicate} is what
     * puts it back -- and a row inside a stated figure: a statement's closing balance, a balance
     * the user typed, the opening the account started from.
     *
     * <p>A row held behind an absolute SET (it was on the account before a statement's closing
     * balance replaced the history) used to move the balance when deleted or edited, though the
     * stated figure had not changed. It now changes that statement's pre-SET snapshot instead, so
     * reversing the SET later restores a balance without the deleted row, and the current balance
     * stays the stated figure.
     */
    private com.finora.accounts.RowBalanceEffect.Location locateEffect(Transaction t) {
        return locateEffect(t, new HashMap<>());
    }

    /** @param chains one SET chain per account, shared by every row of one operation (bulkDelete) so
     *                each link is read once -- see RowBalanceEffect.Chain. */
    private com.finora.accounts.RowBalanceEffect.Location locateEffect(
            Transaction t, Map<UUID, com.finora.accounts.RowBalanceEffect.Chain> chains) {
        Account account = accountRepository.findById(t.getAccountId()).orElse(null);
        // A since-deleted account has no balance left to correct -- same as adjustAccountBalance.
        if (account == null) return new com.finora.accounts.RowBalanceEffect.Location(
                com.finora.accounts.RowBalanceEffect.Where.NOWHERE, null);
        StatementImport statement = t.getStatementImportId() == null ? null
                : statementImportRepository.findById(t.getStatementImportId()).orElse(null);
        return rowBalanceEffect.locate(account, t, statement,
                chains.computeIfAbsent(account.getId(), id -> rowBalanceEffect.chainOf(account)));
    }

    /**
     * A manual entry whose date was just edited, standing where create() would have put it: dated on
     * or before the day the balance is known as of, it is inside that stated figure; after it, it
     * is not. Only for an entry whose effect would otherwise be in the balance itself -- one already
     * inside an older figure (entered before a statement's closing balance set the account, or
     * before the balance was typed) stays there whatever its date, and keeps its record.
     */
    private void recordManualEntryCoverage(Transaction t) {
        if (t.getStatementImportId() != null || t.getSource() != Transaction.Source.MANUAL) return;
        Account account = accountRepository.findById(t.getAccountId()).orElse(null);
        if (account == null) return;
        java.time.LocalDate previous = t.getBalanceCoveredThrough();
        t.setBalanceCoveredThrough(null);
        if (rowBalanceEffect.locate(account, t, null).where() != com.finora.accounts.RowBalanceEffect.Where.BALANCE) {
            t.setBalanceCoveredThrough(previous);
            return;
        }
        java.time.LocalDate knownThrough = balanceCoverage.knownThrough(account);
        boolean inside = knownThrough != null && t.getTxnDate() != null && !t.getTxnDate().isAfter(knownThrough);
        t.setBalanceCoveredThrough(inside ? knownThrough : null);
    }

    private void moveEffect(Transaction t, com.finora.accounts.RowBalanceEffect.Location location, BigDecimal delta) {
        switch (location.where()) {
            case BALANCE -> adjustAccountBalance(t.getAccountId(), delta);
            case SNAPSHOT -> accountRepository.findById(t.getAccountId())
                    .ifPresent(account -> rowBalanceEffect.apply(account, location, delta));
            case NOWHERE -> { }
        }
    }

    /**
     * SEC-13 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). The DB column
     * (NUMERIC(14,2)) already stops anything past 12 integer digits, but as a raw
     * DataIntegrityViolationException rather than a validation error naming the field -- and 12
     * digits (nearly a trillion) is not "a bound," it is the column simply running out of room.
     * This is the actual sanity ceiling: a manually entered amount above what any real bank
     * statement would plausibly contain, rejected with a message the user can act on instead of a
     * generic 409.
     */
    private static final BigDecimal MAX_TRANSACTION_AMOUNT = new BigDecimal("999999999.99");

    /**
     * The whole balance-sign convention (see balanceDelta's doc comment) assumes amount is
     * always a non-negative magnitude, with direction encoded solely by the transaction's type.
     * A negative amount would silently double-invert that math -- e.g. an EXPENSE of -500 on a
     * savings account would ADD 500 to the balance instead of subtracting it. CSV imports are
     * already safe (CsvImportService.parseRow takes the absolute value), but nothing previously
     * stopped this from reaching the manual create/edit paths. The upper bound is SEC-13, added
     * alongside this method rather than as a separate check -- both are the same question (is this
     * a real transaction amount?), and splitting them across two validation layers (this one a
     * service-level ApiException, an upper bound as a Bean Validation annotation on the DTO) would
     * mean a reader has to check two places to know what "valid amount" means here.
     */
    private void requireAmountWithinBounds(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Amount must be greater than zero");
        }
        if (amount.compareTo(MAX_TRANSACTION_AMOUNT) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Amount can't exceed " + MAX_TRANSACTION_AMOUNT + " for a single transaction");
        }
    }

    /**
     * Full edit — the Ledger page's Edit action. Every editable field (date, description,
     * merchant, amount, type, category, notes, tags) can change in one call. If amount or type
     * changes, the account's running balance is corrected by reversing the transaction's old
     * contribution and applying its new one, so Dashboard/Accounts/Reports stay accurate without
     * needing a separate reconciliation pass. Category, when supplied, always counts as a manual
     * decision — there's no "engine suggestion" concept in an edit form the user is typing into.
     */
    @Transactional
    public TransactionDto update(UUID userId, UUID txnId, TransactionDto.UpdateRequest req) {
        Transaction t = getOwned(userId, txnId);

        BigDecimal oldDelta = balanceOf(t);
        // Located before the edit: a date change can move the row in or out of a stated figure.
        com.finora.accounts.RowBalanceEffect.Location oldLocation = locateEffect(t);

        recordUserEdits(t, req);
        if (req.date() != null) t.setTxnDate(req.date());
        if (req.description() != null) {
            t.setDescription(req.description());
            // The counterparty is DERIVED from the narration, so editing the narration has to
            // re-derive it. Without this the row keeps whoever the old description named, and --
            // because it already carries the current classifier version -- the backfill sweep will
            // never revisit it either, so the stale answer becomes permanent. Corrected here rather
            // than left to the sweep, since a user who has just retyped a description is the person
            // most likely to look at the result immediately.
            t.applyCounterpartyTyping(req.description());
        }
        if (req.merchant() != null) t.setMerchant(req.merchant());
        if (req.amount() != null) {
            requireAmountWithinBounds(req.amount());
            t.setAmount(req.amount());
        }
        if (req.type() != null) t.setTxnType(com.finora.util.EnumParsing.parse(Transaction.Type.class, req.type(), "type"));
        if (req.notes() != null) t.setNotes(req.notes());
        if (req.tags() != null) t.setTags(req.tags());

        Category category = null;
        List<Transaction> similar = List.of();
        if (req.categoryName() != null) {
            category = categorizationService.resolveOrCreateCategory(userId, req.categoryName());
            similar = applyChosenCategory(userId, t, category, req.applyTo());
        }

        if (req.date() != null) recordManualEntryCoverage(t);
        Transaction saved = transactionRepository.save(t);
        if (!similar.isEmpty()) transactionRepository.saveAll(similar);

        BigDecimal newDelta = balanceOf(saved);
        com.finora.accounts.RowBalanceEffect.Location newLocation = locateEffect(saved);
        if (oldLocation.equals(newLocation)) {
            moveEffect(saved, newLocation, newDelta.subtract(oldDelta));
        } else {
            moveEffect(saved, oldLocation, oldDelta.negate());
            moveEffect(saved, newLocation, newDelta);
        }

        // Amount/date/type edits can change which surviving transactions look like duplicates or
        // transfer partners of this one, so re-run reconciliation rather than leaving stale flags.
        // A merchant/amount edit can equally change whether this transaction still fits (or now
        // fits) a recurring group, so recurring detection re-runs here too.
        reconciliationService.reconcileForUser(userId);
        recurringService.detectForUser(userId);

        String resolvedCategoryName = category != null
                ? category.getName()
                : categoryNamesById(userId).getOrDefault(saved.getCategoryId(), "Uncategorized");
        auditService.record(userId, "TRANSACTION_UPDATED", "Transaction", txnId, Map.of("amount", saved.getAmount()));
        return TransactionDto.from(saved, resolvedCategoryName);
    }

    /**
     * Marks, before any field is overwritten, which statement-derived fields this edit actually
     * changes -- so a later statement refresh keeps the user's value (see
     * {@link Transaction.EditableField}). Both the web Ledger and the mobile edit sheet send every
     * field on every save, so a field being present is not an edit; only a different value is. A
     * merchant stored as null and submitted as an empty string is not a change: the clients show a
     * missing merchant as an empty box and send it back that way.
     */
    private static void recordUserEdits(Transaction t, TransactionDto.UpdateRequest req) {
        if (req.date() != null && !req.date().equals(t.getTxnDate())) {
            t.markUserEdited(Transaction.EditableField.DATE);
        }
        if (req.description() != null && !req.description().equals(t.getDescription())) {
            t.markUserEdited(Transaction.EditableField.DESCRIPTION);
        }
        if (req.merchant() != null && !blankAsEmpty(req.merchant()).equals(blankAsEmpty(t.getMerchant()))) {
            t.markUserEdited(Transaction.EditableField.MERCHANT);
        }
        if (req.amount() != null && (t.getAmount() == null || req.amount().compareTo(t.getAmount()) != 0)) {
            t.markUserEdited(Transaction.EditableField.AMOUNT);
        }
        if (req.type() != null
                && com.finora.util.EnumParsing.parse(Transaction.Type.class, req.type(), "type") != t.getTxnType()) {
            t.markUserEdited(Transaction.EditableField.TYPE);
        }
    }

    /** "" for null or blank, so the two compare equal; see recordUserEdits. */
    private static String blankAsEmpty(String s) {
        return s == null || s.isBlank() ? "" : s;
    }

    @Transactional
    public TransactionDto updateCategory(UUID userId, UUID txnId, String categoryName) {
        return updateCategory(userId, txnId, categoryName, null);
    }

    /** @param scope which rows the choice reaches, see {@link TransactionDto.CategoryScope}; null keeps
     *               the behaviour from before the choice existed */
    @Transactional
    public TransactionDto updateCategory(UUID userId, UUID txnId, String categoryName,
                                         TransactionDto.CategoryScope scope) {
        Transaction t = getOwned(userId, txnId);
        // A Quick sort answer the user later changed -- how good the answers were. Quick sort's own
        // answer never counts here: it stamps quick_sorted_at only after this method returns.
        if (t.getQuickSortedAt() != null) quickSortMetrics.answerChangedLater();
        String previousCategoryId = String.valueOf(t.getCategoryId());
        Category category = categorizationService.resolveOrCreateCategory(userId, categoryName);
        List<Transaction> similar = applyChosenCategory(userId, t, category, scope);
        Transaction saved = transactionRepository.save(t);
        if (!similar.isEmpty()) transactionRepository.saveAll(similar);
        List<Transaction> edited = new ArrayList<>(similar);
        edited.add(saved);
        reconciliationService.reconcileIfInvestmentExclusionMayChange(userId, edited, category);
        auditService.record(userId, "TRANSACTION_CATEGORY_UPDATED", "Transaction", txnId,
                Map.of("previousCategoryId", previousCategoryId, "newCategory", categoryName,
                        "scope", scope == null ? "UNSPECIFIED" : scope.name(), "similarChanged", similar.size()));
        return TransactionDto.from(saved, category.getName());
    }

    /**
     * What "apply to all similar" would reach from {@code txnId}: see
     * {@link TransactionDto.SimilarSummary}. Read-only.
     */
    @Transactional(readOnly = true)
    public TransactionDto.SimilarSummary similarSummary(UUID userId, UUID txnId) {
        Transaction t = getOwned(userId, txnId);
        List<Transaction> samePayee = samePayeeRows(userId, t);
        int kept = (int) samePayee.stream().filter(Transaction::isCategoryManuallySet).count();
        return new TransactionDto.SimilarSummary(samePayee.size() - kept, kept);
    }

    /**
     * Applies a category the user chose for {@code t}, on {@code t} itself and, for
     * {@link TransactionDto.CategoryScope#SIMILAR}, on every other row from the same payee in the same
     * direction whose category the user has not set by hand. Returns those other rows, changed and
     * not yet saved; {@code t} is changed and not saved either.
     *
     * <p>Learning happens once, from {@code t}: the other rows share its payee, so learning each
     * would only repeat the same lesson. {@code ONLY_THIS} learns nothing and remembers nothing --
     * a one-off must not become the payee's category for every future import.
     */
    private List<Transaction> applyChosenCategory(UUID userId, Transaction t, Category category,
                                                  TransactionDto.CategoryScope scope) {
        markChosen(t, category);
        if (scope == TransactionDto.CategoryScope.ONLY_THIS) return List.of();
        categorizationService.learn(userId, t.getDescription(), category.getId());
        sharedCorpusService.recordObservation(userId, t.getCounterpartyKey(), t.getCounterpartyType(),
                t.getTxnType(), category.getName());
        // Remembered for the payee only when the key names one: pinned to a masked UPI id or a
        // gateway-only name, this choice would file every future payment to a stranger on that key.
        if (com.finora.util.CounterpartyIdentity.identifiesOnePayee(t.getCounterpartyKey())) {
            userMerchantCategoryResolutionService.pin(userId, t.getCounterpartyKey(), t.getTxnType(), category.getId());
        }
        if (scope != TransactionDto.CategoryScope.SIMILAR) return List.of();
        List<Transaction> similar = samePayeeRows(userId, t).stream()
                .filter(other -> !other.isCategoryManuallySet())
                .toList();
        similar.forEach(other -> markChosen(other, category));
        return similar;
    }

    /**
     * Quick sort: the rest of one payee's waiting rows, filed with the category the user just chose
     * for the payee's anchor row (which {@link #updateCategory} already learned from and remembered).
     * One write and one reconciliation for the batch, and no learning: the rows share the anchor's
     * payee, so learning each would only repeat the same lesson -- and doing it row by row through
     * updateCategory ran a reconciliation per row.
     *
     * @return how many rows were filed
     */
    @Transactional
    public int fileWithChosenCategory(UUID userId, List<UUID> ids, String categoryName) {
        if (ids.isEmpty()) return 0;
        Category category = categorizationService.resolveOrCreateCategory(userId, categoryName);
        List<Transaction> owned = getOwnedAll(userId, ids);
        owned.forEach(t -> markChosen(t, category));
        transactionRepository.saveAll(owned);
        reconciliationService.reconcileIfInvestmentExclusionMayChange(userId, owned, category);
        auditService.record(userId, "TRANSACTION_QUICK_SORT_FILED", "Transaction", null,
                Map.of("count", owned.size(), "newCategory", categoryName));
        return owned.size();
    }

    /** The user chose this category: it resolves the review flag, even "Other" picked on purpose. */
    private static void markChosen(Transaction t, Category category) {
        t.setCategoryId(category.getId());
        t.setCategoryManuallySet(true);
        t.setNeedsCategoryReview(false);
        t.setDecisionSource(Transaction.DecisionSource.MANUAL);
        t.setDecisionRuleId(null);
        t.setDecisionConfidence(null);
    }

    /**
     * Other rows with {@code t}'s payee and direction, on the user's live accounts. None when the key
     * does not identify one payee ({@link com.finora.util.CounterpartyIdentity#identifiesOnePayee}):
     * a masked UPI id or a gateway-only name joins strangers, and applying a choice across it would
     * recategorise payments to other people. A deleted account's rows stay out: the user cannot see
     * them, so they must not be counted in the question or changed by the answer.
     */
    private List<Transaction> samePayeeRows(UUID userId, Transaction t) {
        if (!com.finora.util.CounterpartyIdentity.identifiesOnePayee(t.getCounterpartyKey())) return List.of();
        List<UUID> liveAccountIds = accountRepository.findByUserId(userId).stream().map(Account::getId).toList();
        if (liveAccountIds.isEmpty()) return List.of();
        return transactionRepository.findByUserIdAndCounterpartyKeyAndTxnTypeAndIdNotAndAccountIdIn(
                userId, t.getCounterpartyKey(), t.getTxnType(), t.getId(), liveAccountIds);
    }

    /**
     * "This is not a duplicate" -- the decision the engine cannot make and, until now, could only
     * be told during an import review.
     *
     * <h2>The gap this closes</h2>
     *
     * <p>{@code ReconciliationService}'s duplicate pass groups on account, date, amount and
     * description and flags every member but the earliest. It cannot distinguish "the same
     * statement uploaded twice" from "two metro fares on one day", which is exactly why
     * {@code notDuplicateConfirmedAt} exists -- and that field was writable from precisely one
     * place in the application, {@code ImportService.confirm}, reachable only from the import
     * review screen.
     *
     * <p>So a user who entered two identical transactions by hand had the second one flagged
     * {@code DUPLICATE} on the very next write, silently excluded from income, expenses, category
     * spend, budgets and every report, with no affordance anywhere to say otherwise. Worse, it was
     * excluded inconsistently: {@code Account.balance} counts it, because a duplicate-flagged row
     * is still a real ledger row. The ledger and the dashboard disagreed by that amount and the
     * user had no way to reconcile them.
     *
     * <p>Two identical same-day charges is not an exotic case -- it is a commute, a round of
     * coffees, a split bill paid twice.
     *
     * <h2>Why this also clears the pointer rather than only stamping the flag</h2>
     *
     * <p>{@code notDuplicateConfirmedAt} stops the NEXT pass re-flagging the row; on its own it
     * would leave the current {@code isDuplicateOf} and {@code DUPLICATE} status sitting there, so
     * the row would stay excluded until something else happened to touch it. The user asked for
     * this row to count, so it counts now.
     *
     * <p>Reconciliation re-runs afterwards for the same reason every other write path re-runs it:
     * a row returning to OK can complete or break a pattern elsewhere, and a third genuinely
     * accidental copy must still be flagged against this one.
     *
     * <h2>Refused when the owning statement has since been superseded</h2>
     *
     * <p>{@code StatementImportService.supersede} only ever touches {@code OK}-status rows (see
     * its own doc comment) -- a row already sitting at {@code DUPLICATE} is invisible to that
     * filter, so it survives untouched when its statement is marked replaced, still reachable from
     * the Dashboard's "detected duplicates" widget with no indication its statement is stale.
     * Un-duplicating it here would resurrect a row from data Finora has already recorded as
     * replaced -- into every report, and (see below) potentially into the balance a second time.
     * {@code supersede} itself already refuses to act on an already-superseded statement; this is
     * the same refusal from the other side.
     */
    @Transactional
    public TransactionDto confirmNotDuplicate(UUID userId, UUID txnId) {
        Transaction t = getOwned(userId, txnId);

        if (t.getStatementImportId() != null) {
            statementImportRepository.findById(t.getStatementImportId())
                    .filter(si -> si.getSupersededBy() != null)
                    .ifPresent(si -> {
                        throw new ApiException(HttpStatus.BAD_REQUEST,
                                "This transaction's statement has since been replaced by a later "
                                        + "re-upload of the same period, so it cannot be confirmed as "
                                        + "not a duplicate -- doing so would resurrect a row from "
                                        + "data that has already been superseded. Check the "
                                        + "replacement statement instead.");
                    });
        }

        // What the mark did to Account.balance, read before the mark and its record are cleared.
        boolean reversedAtMark = t.isDuplicateBalanceReversed();
        t.setNotDuplicateConfirmedAt(java.time.Instant.now());
        t.setIsDuplicateOf(null);
        t.setDuplicateBalanceReversed(false);
        t.setDuplicateBalanceAnchorId(null);
        t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
        t.setReconciliationExplanation(null);
        Transaction saved = transactionRepository.save(t);

        // The mark took this row's net effect off Account.balance when reconciliation wrote it
        // (BH-003, ReconciliationService.reverseBalanceContribution) -- for every row whose effect
        // was in the balance to begin with: a manual entry, or a row of an ADDITIVE statement import,
        // unless a later stated closing balance replaced the history it belonged to. Confirming it
        // "not a duplicate" means it counts in every report again, so the balance must count it
        // again too, or it stays permanently short by this row's amount with no way to self-correct.
        // The row records what the mark did (Transaction.duplicateBalanceReversed), so this reads
        // the record rather than re-deriving it from state that may have changed since the mark.
        // V228 wrote the record for marks that predate it.
        // Put back where the row's effect belongs NOW, not blindly on the balance: if a statement's
        // closing balance set the account after the mark, the bank's figure already holds this real
        // row, and it goes into that statement's pre-SET snapshot instead (locateEffect). Adding it
        // to the balance overstated it by the row.
        if (reversedAtMark) {
            moveEffect(saved, locateEffect(saved), balanceOf(saved));
        }

        reconciliationService.reconcileForUser(userId);
        recurringService.detectForUser(userId);

        auditService.record(userId, "TRANSACTION_CONFIRMED_NOT_DUPLICATE", "Transaction", txnId,
                Map.of("amount", saved.getAmount(), "date", String.valueOf(saved.getTxnDate())));

        // Deliberately last, not right after save(t) above: everything between here and there
        // (the balance adjustment, a full reconciliation re-run, recurring detection, the audit
        // write) still runs inside this same @Transactional method and can still throw. A
        // Micrometer counter is not transactional -- nothing rolls it back -- so incrementing it
        // any earlier would count an override that a later failure in THIS method then undoes.
        // This placement can't close the outer window (Spring's proxy commits after this method
        // returns, so a commit-time failure is still possible), but it removes the much larger,
        // entirely-avoidable one: this method's own later steps failing before this line is ever
        // reached.
        reconciliationMetrics.duplicateOverridden(saved.getSource());

        return TransactionDto.from(saved,
                categoryNamesById(userId).getOrDefault(saved.getCategoryId(), "Uncategorized"));
    }

    /**
     * Plan 6, Track B. Clears {@code pendingBankCorrection} once the user has seen the correction
     * detail (old vs. new, surfaced from the row's own AuditLog trail) and explicitly acknowledged
     * it. Deliberately its own action, not folded into {@code updateCategory}/{@code update}'s
     * existing "an explicit edit always resolves the review flag" pattern: those clear
     * needsCategoryReview because editing the category IS the act of reviewing it, but editing a
     * category is not the same act as acknowledging a bank-reported value correction the user may
     * not have even seen yet. Never touches amount/description/etc. -- this only ever clears the
     * flag, consistent with round 3's "preserve, don't overwrite" decision (see
     * AccountAggregatorTransactionDiffService's own class doc).
     */
    @Transactional
    public TransactionDto acknowledgeBankCorrection(UUID userId, UUID txnId) {
        Transaction t = getOwned(userId, txnId);
        t.setPendingBankCorrection(false);
        Transaction saved = transactionRepository.save(t);
        auditService.record(userId, "ACCOUNT_AGGREGATOR_CORRECTION_ACKNOWLEDGED", "Transaction", txnId);
        return TransactionDto.from(saved,
                categoryNamesById(userId).getOrDefault(saved.getCategoryId(), "Uncategorized"));
    }

    /** The AuditLog actions Plan 6, Track B ever writes against a Transaction -- see
     *  AccountAggregatorTransactionDiffService and this class's own acknowledgeBankCorrection. */
    private static final Set<String> BANK_CORRECTION_ACTIONS = Set.of(
            "ACCOUNT_AGGREGATOR_TRANSACTION_CORRECTED", "ACCOUNT_AGGREGATOR_TRANSACTION_MISSING",
            "ACCOUNT_AGGREGATOR_CORRECTION_ACKNOWLEDGED");

    /**
     * Plan 6, Track B. The old-vs-new detail behind a {@code pendingBankCorrection} badge --
     * ownership-checked the same way every other single-transaction read/write here is, then
     * narrowed to just this row's own correction-related AuditLog events (not its full audit
     * history, which also carries unrelated actions like category edits or transfer marking that
     * have no place in a "what did the bank change" view).
     */
    @Transactional(readOnly = true)
    public List<TransactionDto.BankCorrectionHistoryEntry> correctionHistory(UUID userId, UUID txnId) {
        getOwned(userId, txnId); // ownership check only -- the entity itself isn't needed further
        return auditLogRepository.findByEntityIdOrderByCreatedAtAsc(txnId).stream()
                .filter(log -> BANK_CORRECTION_ACTIONS.contains(log.getAction()))
                .map(log -> new TransactionDto.BankCorrectionHistoryEntry(
                        log.getAction(), log.getMetadata(), log.getCreatedAt()))
                .toList();
    }

    /**
     * "Mark as a transfer" -- the user-facing counterpart to ReconciliationService's own
     * auto-detection pass, for exactly the pairs that pass can't reach: no own-account
     * relationship identifier on file, outside its date window, or an amount that doesn't match
     * closely enough for its tolerance. Sets the same three legacy columns
     * (isTransfer/transferPairId/reconciliationStatus) the auto pass sets, via
     * {@code reconciliationService.explainManualTransfer}/{@code recordManualTransferEdges} for the
     * explanation and graph-edge halves -- see those methods' own doc comments for why that split
     * exists (package-private {@code ReconciliationExplanation}, and this class has no access to
     * the graph edge's confidence/source-trust helpers either).
     *
     * <p>Validates the same structural shape a real transfer must have -- different accounts,
     * opposite direction -- but deliberately NOT the auto pass's amount tolerance or date window:
     * those are heuristics for an INFERRED match, and a fee-adjusted or delayed transfer a human
     * can see clearly is exactly the case a manual override exists for.
     */
    @Transactional
    public TransactionDto markTransfer(UUID userId, UUID txnId, UUID pairedTxnId) {
        if (txnId.equals(pairedTxnId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A transaction cannot be its own transfer pair.");
        }
        Transaction a = getOwned(userId, txnId);
        Transaction b = getOwned(userId, pairedTxnId);
        if (a.getAccountId().equals(b.getAccountId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A transfer must be between two different accounts.");
        }
        if (a.getTxnType() == b.getTxnType()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "A transfer needs one income and one expense leg -- these are both " + a.getTxnType() + ".");
        }
        // A transfer that already has a partner must be unmarked first. A one-sided own-account
        // transfer (no partner yet -- Plan 3, found by the user's own name) is exactly the row a
        // user links to its other leg, so it is accepted and becomes an ordinary pair.
        if ((a.isTransfer() && a.getTransferPairId() != null) || (b.isTransfer() && b.getTransferPairId() != null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "One of these transactions is already marked as a transfer -- unmark it first.");
        }

        a.setTransfer(true); b.setTransfer(true);
        a.setTransferPairId(b.getId()); b.setTransferPairId(a.getId());
        // The user is asserting this IS a transfer now, which outranks any earlier "not a
        // transfer" ruling on either side -- same "the latest explicit human decision wins"
        // precedent as confirmNotDuplicate clearing isDuplicateOf above.
        a.setTransferRejectedAt(null); b.setTransferRejectedAt(null);
        a.setReconciliationStatus(Transaction.ReconciliationStatus.TRANSFER);
        b.setReconciliationStatus(Transaction.ReconciliationStatus.TRANSFER);
        a.setReconciliationExplanation(reconciliationService.explainManualTransfer(a, b));
        b.setReconciliationExplanation(reconciliationService.explainManualTransfer(b, a));
        transactionRepository.saveAll(List.of(a, b));
        reconciliationService.recordManualTransferEdges(userId, a, b);

        auditService.record(userId, "TRANSACTION_MARKED_TRANSFER", "Transaction", txnId,
                Map.of("pairedTransactionId", pairedTxnId.toString()));

        return TransactionDto.from(a, categoryNamesById(userId).getOrDefault(a.getCategoryId(), "Uncategorized"));
    }

    /**
     * "Unmark as a transfer" -- reverts both legs to {@code OK} and stamps
     * {@code transferRejectedAt} on the transaction the caller named, so ReconciliationService's
     * auto pass (which reruns after every create/edit/delete/import for this user) does not
     * silently re-pair it right back on its next run. See {@code Transaction.transferRejectedAt}'s
     * own doc comment (V191) -- same shape and same reason as {@code notDuplicateConfirmedAt}.
     *
     * <p>Idempotent: unmarking a transaction that isn't currently a transfer is a no-op, not an
     * error -- a double-tap or a retried request must not surface an error for an action that
     * already happened.
     *
     * <p>Only the named transaction's rejection is sticky. Its former partner reverts to {@code OK}
     * too (a transfer is a pairing, not a one-sided fact -- leaving it at TRANSFER with the other
     * side now at OK would be internally inconsistent), but is NOT itself marked rejected: if it
     * still looks like a transfer on the next run, matched against some OTHER partner, that is a
     * separate, still-live pairing the user hasn't ruled on.
     */
    @Transactional
    public TransactionDto unmarkTransfer(UUID userId, UUID txnId) {
        Transaction a = getOwned(userId, txnId);
        if (!a.isTransfer()) {
            return TransactionDto.from(a, categoryNamesById(userId).getOrDefault(a.getCategoryId(), "Uncategorized"));
        }
        UUID pairedId = a.getTransferPairId();

        a.setTransfer(false);
        a.setTransferPairId(null);
        a.setTransferRejectedAt(java.time.Instant.now());
        a.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
        a.setReconciliationExplanation(null);

        List<Transaction> changed = new java.util.ArrayList<>(List.of(a));
        if (pairedId != null) {
            transactionRepository.findById(pairedId)
                    .filter(b -> b.getUserId().equals(userId))
                    .ifPresent(b -> {
                        b.setTransfer(false);
                        b.setTransferPairId(null);
                        b.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
                        b.setReconciliationExplanation(null);
                        changed.add(b);
                    });
        }
        transactionRepository.saveAll(changed);
        transactionGraphService.rejectEdgesTouchingTransactions(changed.stream().map(Transaction::getId).toList());

        auditService.record(userId, "TRANSACTION_UNMARKED_TRANSFER", "Transaction", txnId, Map.of());

        return TransactionDto.from(a, categoryNamesById(userId).getOrDefault(a.getCategoryId(), "Uncategorized"));
    }

    /**
     * Backs POST /api/v1/merchants/{merchantId}/confirm-category (spec §5.5) -- the
     * merchant-centric counterpart to updateCategory() above. Functionally the same three
     * things (set the transaction's category, mark it manually-set/reviewed, record the
     * confirmation against the merchant's learned distribution via
     * categorizationService.learn()) -- categorizationService.learn() already resolves the
     * transaction's merchant and calls MerchantLearningService.confirm() internally, so this
     * was never missing that wiring, only the merchant-centric endpoint shape spec'd by §5.5
     * (categoryId + a specific transaction to apply it to, rather than updateCategory()'s
     * transaction-centric categoryName).
     *
     * The merchantId check below is what the spec means by "replaces...for merchant-resolved
     * transactions" -- confirming against the wrong merchant (or a transaction with no resolved
     * merchant at all) is rejected rather than silently confirming against mismatched learning
     * data; the spec's own text says unresolved transactions "fall back to the existing simpler
     * endpoint" (updateCategory()), not this one.
     *
     * Bug fix: this recorded TRANSACTION_CATEGORY_UPDATED with no actingAdminId at all. Its
     * self-service caller (MerchantController) has since been retired entirely -- per
     * AdminUserMerchantController's own doc comment, this is now the ONLY way anyone, including
     * the account's own owner, can apply a merchant-centric category choice -- so every single
     * call to this method is in fact an admin acting on a user's behalf, indistinguishable in the
     * audit trail from the user confirming their own category. Same bug class, same fix, as the
     * actorId threading already done for RelationshipService/MerchantService/RoleService/
     * RuleService/AccountService/this class's own delete().
     */
    @Transactional
    public TransactionDto confirmMerchantCategory(UUID userId, UUID merchantId, UUID txnId, UUID categoryId, UUID actingAdminId) {
        Transaction t = getOwned(userId, txnId);
        if (t.getMerchantId() == null || !t.getMerchantId().equals(merchantId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "This transaction isn't resolved to the given merchant -- use PATCH /transactions/{id}/category instead.");
        }
        Category category = categoryRepository.findById(categoryId)
                .filter(c -> c.getUserId().equals(userId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Category not found."));

        String previousCategoryId = String.valueOf(t.getCategoryId());
        t.setCategoryId(category.getId());
        t.setNeedsCategoryReview(false);
        t.setCategoryManuallySet(true);
        t.setDecisionSource(Transaction.DecisionSource.MANUAL);
        t.setDecisionRuleId(null);
        t.setDecisionConfidence(null);
        categorizationService.learn(userId, t.getDescription(), category.getId());
        sharedCorpusService.recordObservation(userId, t.getCounterpartyKey(), t.getCounterpartyType(),
                t.getTxnType(), category.getName());
        userMerchantCategoryResolutionService.pin(userId, t.getCounterpartyKey(), t.getTxnType(), category.getId());
        Transaction saved = transactionRepository.save(t);
        reconciliationService.reconcileIfInvestmentExclusionMayChange(userId, List.of(saved), category);
        auditService.record(userId, "TRANSACTION_CATEGORY_UPDATED", "Transaction", txnId,
                Map.of("previousCategoryId", previousCategoryId, "newCategory", category.getName(),
                        "actorId", actingAdminId.toString()));
        return TransactionDto.from(saved, category.getName());
    }

    /** Backs the "Ask Once, Learn Forever" review queue — every transaction the engine wasn't
     *  confident about, waiting on exactly one user decision each.
     *
     *  <p>Deliberately excludes any transaction that {@link TransactionGroupingService} already
     *  offers as part of a same-merchant bulk group (2+ needs-review transactions for one
     *  merchant): without this, a grouped transaction was asked about twice — once row-by-row
     *  here, once again via the bulk "Categorize a whole merchant at once" card — since both
     *  queries drew from the same unfiltered needs-review set with nothing making them disjoint.
     *  Also excludes {@code DUPLICATE}-status transactions, matching the grouping query, since
     *  those are resolved through the duplicate-review flow instead. */
    @Transactional(readOnly = true)
    public List<TransactionDto> needsReview(UUID userId) {
        Map<UUID, String> namesById = categoryNamesById(userId);
        // Deleted-account leak (see DashboardService.summarize for the original fix): a deleted
        // account's transactions deliberately keep deleted_at unset, so the unscoped finder would
        // keep surfacing them in this review queue forever, not just during
        // StatementImportService's 7-day grace window.
        List<UUID> liveAccountIds = accountRepository.findByUserId(userId).stream()
                .map(Account::getId).toList();
        List<Transaction> needsReview = liveAccountIds.isEmpty() ? List.of()
                : transactionRepository.findByUserIdAndNeedsCategoryReviewTrueAndAccountIdInOrderByTxnDateDesc(
                        userId, liveAccountIds);
        if (needsReview.isEmpty()) return List.of();

        Set<UUID> groupedTransactionIds = new HashSet<>();
        for (TransactionGroupingService.MerchantGroup group : transactionGroupingService.groupNeedsReviewByMerchant(userId)) {
            groupedTransactionIds.addAll(group.transactionIds());
        }

        return needsReview.stream()
                .filter(t -> t.getReconciliationStatus() != Transaction.ReconciliationStatus.DUPLICATE)
                .filter(t -> !groupedTransactionIds.contains(t.getId()))
                .map(t -> TransactionDto.from(t, namesById.getOrDefault(t.getCategoryId(), "Uncategorized")))
                .toList();
    }

    /**
     * Bug fix: this recorded TRANSACTION_DELETED against only the target user, with no
     * actingAdminId anywhere -- AdminTransactionController (support-assisted transaction deletion)
     * calls this exact same method with the target userId sourced from the path, so an admin
     * deleting a user's transaction was indistinguishable in the audit trail from the user deleting
     * their own. Same bug class, same fix, as the actorId threading already done for
     * RelationshipService/MerchantService/RoleService/RuleService/AccountService.
     */
    @Transactional
    public void delete(UUID userId, UUID txnId, UUID actingAdminId) {
        Transaction t = getOwned(userId, txnId);
        clearReconciliationPointersTo(List.of(t.getId()));
        moveEffect(t, locateEffect(t), balanceOf(t).negate());
        transactionRepository.delete(t); // soft delete via @SQLDelete on the entity
        // Removing a transaction can break a recurring group's pattern (e.g. deleting one of
        // three regularly-spaced charges), same reasoning as the reconciliation re-run below.
        reconciliationService.reconcileForUser(userId);
        recurringService.detectForUser(userId);
        auditService.record(userId, "TRANSACTION_DELETED", "Transaction", txnId,
                Map.of("amount", t.getAmount(), "description", String.valueOf(t.getDescription()),
                        "actorId", actingAdminId.toString()));
    }

    // --- Statement refresh (imports.refresh.StatementRefreshService) -------------------------------
    // A refresh patches a statement's rows in place. Each change moves the account balance exactly
    // the way the matching user action does -- an edit, a delete -- through the one owner of that
    // rule (locateEffect/moveEffect). None of these reconciles, detects recurring or audits: the
    // refresh does each once, for the whole statement, after every row is patched.

    /**
     * Applies a statement's corrected reading to one of its rows. Same balance rule as {@link #update}:
     * the row's old effect comes out of wherever it sat, its new one goes where it now belongs.
     */
    @Transactional
    public Transaction correctFromStatement(Transaction t, java.util.function.Consumer<Transaction> patch) {
        BigDecimal oldDelta = balanceOf(t);
        com.finora.accounts.RowBalanceEffect.Location oldLocation = locateEffect(t);
        patch.accept(t);
        Transaction saved = transactionRepository.save(t);
        BigDecimal newDelta = balanceOf(saved);
        com.finora.accounts.RowBalanceEffect.Location newLocation = locateEffect(saved);
        if (oldLocation.equals(newLocation)) {
            moveEffect(saved, newLocation, newDelta.subtract(oldDelta));
        } else {
            moveEffect(saved, oldLocation, oldDelta.negate());
            moveEffect(saved, newLocation, newDelta);
        }
        return saved;
    }

    /**
     * Inserts a row its statement always had but an older parser missed. Its balance effect is
     * placed as of the statement's import, where that statement's other rows' effects are -- see
     * {@link com.finora.accounts.RowBalanceEffect#locate(Account, Transaction, StatementImport,
     * com.finora.accounts.RowBalanceEffect.Chain, java.time.Instant)}.
     */
    @Transactional
    public Transaction insertFromStatement(Transaction t, StatementImport statement) {
        Transaction saved = transactionRepository.save(t);
        Account account = accountRepository.findById(saved.getAccountId()).orElse(null);
        if (account != null) {
            com.finora.accounts.RowBalanceEffect.Location location = rowBalanceEffect.locate(
                    account, saved, statement, rowBalanceEffect.chainOf(account), statement.getImportedAt());
            moveEffect(saved, location, balanceOf(saved));
        }
        return saved;
    }

    /**
     * Before a refresh corrects these rows' amount, type or date: the reconciliation decided against
     * the old values -- a transfer pair, a refund link, a duplicate mark -- is undone, so the
     * reconciliation run after the refresh decides again from the corrected ones. Otherwise a pair
     * matched on a misread amount would outlive the correction (a 20.00 purchase left "transferred"
     * against a 2.00 credit). What the user decided (a transfer they marked, a pairing they
     * rejected) is kept.
     */
    @Transactional
    public void releaseReconciliationForRefresh(UUID userId, List<UUID> ids) {
        if (ids.isEmpty()) return;
        java.util.Set<UUID> userDecided = transactionGraphService.releaseMachineEdgesTouching(ids);
        java.util.Set<Transaction> dirty = new java.util.LinkedHashSet<>();
        for (Transaction t : getOwnedAll(userId, ids)) {
            if (t.isTransfer() && !userDecided.contains(t.getId())) {
                UUID partnerId = t.getTransferPairId();
                releaseTransfer(t);
                dirty.add(t);
                if (partnerId != null) {
                    transactionRepository.findById(partnerId).filter(p -> p.getUserId().equals(userId))
                            .filter(p -> t.getId().equals(p.getTransferPairId()) || p.getTransferPairId() == null)
                            .ifPresent(p -> { releaseTransfer(p); dirty.add(p); });
                }
            }
            if (t.getRefundOfTransactionId() != null && !userDecided.contains(t.getId())) {
                t.setRefundOfTransactionId(null);
                t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
                dirty.add(t);
            }
            if (t.getIsDuplicateOf() != null) {
                releaseDuplicate(t);
                dirty.add(t);
            }
        }
        for (Transaction r : transactionRepository.findByRefundOfTransactionIdIn(ids)) {
            if (userDecided.contains(r.getId())) continue;
            r.setRefundOfTransactionId(null);
            r.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
            dirty.add(r);
        }
        for (Transaction d : transactionRepository.findByIsDuplicateOfIn(ids)) {
            releaseDuplicate(d);
            dirty.add(d);
        }
        if (!dirty.isEmpty()) transactionRepository.saveAll(dirty);
    }

    private static void releaseTransfer(Transaction t) {
        t.setTransfer(false);
        t.setTransferPairId(null);
        t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
        t.setReconciliationExplanation(null);
    }

    /** A duplicate mark undone, its balance effect put back if the mark had taken it out -- the same
     *  restore {@link #clearReconciliationPointersTo} does. A row of a replaced statement stays
     *  SUPERSEDED: its rows no longer count either way. */
    private void releaseDuplicate(Transaction t) {
        boolean reversedAtMark = t.isDuplicateBalanceReversed();
        t.setIsDuplicateOf(null);
        t.setDuplicateBalanceReversed(false);
        boolean superseded = t.getStatementImportId() != null && statementImportRepository.findById(t.getStatementImportId())
                .map(si -> si.getSupersededBy() != null).orElse(false);
        if (superseded) {
            t.setReconciliationStatus(Transaction.ReconciliationStatus.SUPERSEDED);
            return;
        }
        t.setDuplicateBalanceAnchorId(null);
        t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
        if (reversedAtMark) moveEffect(t, locateEffect(t), balanceOf(t));
    }

    /**
     * Removes rows a statement no longer contains. Same as {@link #bulkDelete} -- reconciliation
     * pointers cleared, each row's effect reversed, soft-deleted -- and the rows' graph edges are
     * rejected too: a link to a row that was never on the statement must not keep settling a
     * payment or a transfer.
     */
    @Transactional
    public void removeFromStatement(UUID userId, List<UUID> ids) {
        if (ids.isEmpty()) return;
        List<Transaction> owned = getOwnedAll(userId, ids);
        clearReconciliationPointersTo(owned.stream().map(Transaction::getId).toList());
        Map<UUID, com.finora.accounts.RowBalanceEffect.Chain> chains = new HashMap<>();
        for (Transaction t : owned) {
            moveEffect(t, locateEffect(t, chains), balanceOf(t).negate());
            transactionRepository.delete(t);
        }
        transactionGraphService.rejectEdgesTouchingTransactions(owned.stream().map(Transaction::getId).toList());
    }

    /**
     * Bug 36: recorded no actorId at all, unlike {@link #delete}, which was fixed for the exact
     * same reason -- an admin bulk-deleting a user's transactions was indistinguishable from the
     * user doing it themself, for the higher-impact operation of the pair.
     */
    @Transactional
    public void bulkDelete(UUID userId, List<UUID> ids, UUID actingAdminId) {
        List<Transaction> owned = getOwnedAll(userId, ids);
        clearReconciliationPointersTo(owned.stream().map(Transaction::getId).toList());
        Map<UUID, com.finora.accounts.RowBalanceEffect.Chain> chains = new HashMap<>();
        for (Transaction t : owned) {
            moveEffect(t, locateEffect(t, chains), balanceOf(t).negate());
            transactionRepository.delete(t);
        }
        reconciliationService.reconcileForUser(userId);
        recurringService.detectForUser(userId);
        auditService.record(userId, "TRANSACTION_BULK_DELETED", "Transaction", null,
                Map.of("count", ids.size(), "ids", ids, "actorId", actingAdminId.toString()));
    }

    /**
     * Any surviving transaction that had been paired with one of the removed ones (as a
     * duplicate, transfer partner, or refund target) gets its reconciliation flags reset first,
     * rather than being left pointing at a row that no longer visibly exists — same cleanup
     * StatementImportService.delete() already does for whole-statement deletes, now shared by
     * single/bulk transaction delete too, which never did this before.
     *
     * Bug fix: the refund case was missing entirely -- deleting the EXPENSE side of a matched
     * refund pair left the INCOME row's refundOfTransactionId dangling (pointing at a row that no
     * longer exists) AND stuck at ReconciliationStatus.REFUND forever, since reconcileForUser()
     * only ever matches a fresh REFUND, it never re-validates or clears an existing one. That
     * silently kept excluding real income from DashboardService's totals (REFUND rows are
     * excluded there the same way DUPLICATE/TRANSFER are) with no way to self-correct. Resetting
     * to OK here lets the next reconciliation pass re-evaluate it like any other income row.
     */
    private void clearReconciliationPointersTo(List<UUID> removedIds) {
        if (removedIds.isEmpty()) return;
        java.util.Set<UUID> removed = new java.util.HashSet<>(removedIds);
        // BH-056: written once at the end rather than a save() per row. ReconciliationService made
        // exactly this change for exactly this reason -- Hibernate's configured batch_size and
        // order_updates can do nothing for writes issued one statement at a time -- and this
        // method, which runs on the same delete paths, kept the per-row form.
        //
        // A LinkedHashSet because one surviving row can be reached by more than one of the three
        // lookups (a transfer partner that is also a refund target), and Transaction has no
        // equals/hashCode override, so this de-duplicates by identity while keeping write order
        // deterministic -- the same reasoning ReconciliationService's own `dirty` set carries.
        //
        // `removed` is a Set rather than the original List: contains() ran per candidate row
        // against a list of up to 500 ids, three times over.
        java.util.Set<Transaction> dirty = new java.util.LinkedHashSet<>();
        java.util.Map<UUID, Boolean> supersededImports = new java.util.HashMap<>();
        for (Transaction t : transactionRepository.findByIsDuplicateOfIn(removedIds)) {
            if (removed.contains(t.getId())) continue;
            // Un-marking makes this survivor counted again. If BH-003 took its contribution off
            // when it was marked (recorded as Transaction.duplicateBalanceReversed -- see
            // locateEffect), it goes back on now, the same way confirmNotDuplicate puts it
            // back. Without this, deleting the canonical row reversed the canonical's contribution
            // AND left the survivor's off: the ledger kept one real transaction and the balance
            // reflected none.
            boolean reversedAtMark = t.isDuplicateBalanceReversed();
            t.setIsDuplicateOf(null);
            t.setDuplicateBalanceReversed(false);
            // A survivor whose own statement has since been superseded is not resurrected: it
            // stays out of every total as SUPERSEDED and nothing goes back on the balance -- the
            // same refusal confirmNotDuplicate makes for that row. Its held-anchor record, if any,
            // is kept: the effect is still in that SET's snapshot.
            if (t.getStatementImportId() != null && supersededImports.computeIfAbsent(t.getStatementImportId(),
                    id -> statementImportRepository.findById(id).map(si -> si.getSupersededBy() != null).orElse(false))) {
                t.setReconciliationStatus(Transaction.ReconciliationStatus.SUPERSEDED);
                dirty.add(t);
                continue;
            }
            t.setDuplicateBalanceAnchorId(null);
            t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
            // Where its effect belongs now -- see confirmNotDuplicate. The mark is already cleared
            // on `t` above, so it is located as the counted row it is again.
            if (reversedAtMark) moveEffect(t, locateEffect(t), balanceOf(t));
            dirty.add(t);
        }
        for (Transaction t : transactionRepository.findByTransferPairIdIn(removedIds)) {
            if (removed.contains(t.getId())) continue;
            t.setTransfer(false);
            t.setTransferPairId(null);
            t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
            dirty.add(t);
        }
        for (Transaction t : transactionRepository.findByRefundOfTransactionIdIn(removedIds)) {
            if (removed.contains(t.getId())) continue;
            t.setRefundOfTransactionId(null);
            t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
            dirty.add(t);
        }
        if (!dirty.isEmpty()) transactionRepository.saveAll(dirty);
    }

    /**
     * WI1A — the last synchronous batch learning path, moved onto the queue WI1 built.
     *
     * <p>This used to call {@code categorizationService.learn} inline, once per id, up to
     * {@code TransactionDto.MAX_BULK_IDS} (500) times inside this one transaction. That is the
     * import path's exact pre-WI1 shape and it carried the same defect: {@code
     * MerchantLearningService.confirm} does a check-then-act against
     * {@code UNIQUE(user_id, merchant_id, category_id)}, so one lost race threw a constraint
     * violation that poisoned this transaction and rolled back every one of the 500
     * recategorizations the user had just asked for — including the 499 that had nothing to do with
     * the merchant that lost.
     *
     * <p>{@code queueLearning} writes the event row in THIS transaction, so a bulk action that
     * fails for any other reason takes its queued learning with it, and defers only the applying.
     * See {@code CategorizationService.queueLearning} for why the boundary is drawn there and not
     * one line either side of it.
     *
     * <p>Single, interactive recategorization ({@link #updateCategory}, {@link #confirmMerchantCategory},
     * {@link #create}) deliberately stays synchronous — see {@code CategorizationService.learn}.
     */
    /** Bug 36: same missing-actorId gap as {@link #bulkDelete}, same fix. */
    @Transactional
    public void bulkRecategorize(UUID userId, List<UUID> ids, String categoryName, UUID actingAdminId) {
        Category category = categorizationService.resolveOrCreateCategory(userId, categoryName);
        // BH-057: one query for the whole list rather than one per id -- see getOwnedAll.
        List<Transaction> owned = getOwnedAll(userId, ids);
        for (Transaction t : owned) {
            t.setCategoryId(category.getId());
            t.setNeedsCategoryReview(false); // an explicit bulk choice resolves the review flag too — see updateCategory()
            t.setCategoryManuallySet(true);
            t.setDecisionSource(Transaction.DecisionSource.MANUAL);
            t.setDecisionRuleId(null);
            t.setDecisionConfidence(null);
            // Bug fix: queueLearning -> MerchantNormalizationEngine.resolve() CREATES a merchant on
            // a miss (see that method's own doc). Every row CounterpartyGroupReviewCard's "Apply to
            // N transactions" targets was chosen BECAUSE it had no merchant match -- so calling this
            // unconditionally meant every bulk-apply from that card silently created a new Merchant
            // row for a PERSON. Verified concretely, not assumed: extractMerchant("UPI-SUNIL VERMA-
            // sampleuser@ybl-REF61") normalizes to "upi sunil verma sampleuser", which
            // resolve()/createMerchantAndAlias persisted as a real canonical_name -- a person's name
            // and UPI handle fragment, stored and later surfaced as if it were a business. The engine
            // now names a new merchant from a structured narration's payee field instead ("Sunil
            // Verma" here), which drops the handle fragment but is still a person's name stored as a
            // merchant, so the guard below stands unchanged.
            // Nothing here sets t.setMerchantId(...), so the ghost merchant is never attached to
            // THIS row -- but a LATER transaction from the same person, sharing the same grouping
            // key, would resolve to it and inherit a learned category "suggestion" from a
            // "merchant" that is really just one person's name. That is the exact who/what-for
            // conflation the counterparty layer (CounterpartyClassifier, CounterpartyIdentity) was
            // built to keep apart -- see MerchantIdentityLookup's own doc on why that layer reads
            // CategoryRules rather than the reverse.
            //
            // A BUSINESS counterparty is excluded from this guard on purpose: a business with no
            // merchant record yet legitimately deserves one created here, the same onboarding this
            // bulk action already provides for FINANCIAL_INSTITUTION/GOVERNMENT rows and for every
            // other bulk-recategorize caller. Scoped to PERSON specifically because that is the only
            // type this codebase's own counterparty layer can assert is never a merchant.
            if (t.getCounterpartyType() != CounterpartyType.PERSON) {
                categorizationService.queueLearning(userId, t.getDescription(), category.getId());
            }
            transactionRepository.save(t);
        }
        // Once for the whole batch, after every row has its new category.
        reconciliationService.reconcileIfInvestmentExclusionMayChange(userId, owned, category);
        auditService.record(userId, "TRANSACTION_BULK_RECATEGORIZED", "Transaction", null,
                Map.of("count", ids.size(), "newCategory", categoryName, "actorId", actingAdminId.toString()));
    }

    /**
     * BH-057. The owned rows for a whole bulk id list, in one query.
     *
     * <p>{@code bulkDelete} and {@code bulkRecategorize} called {@link #getOwned} per id -- up to
     * {@code TransactionDto.MAX_BULK_IDS} (500) {@code findById} round trips inside one
     * transaction, before the writes and before the two full-history reconciliation passes that
     * follow. The bound is correct and stays; the round trips were free to remove.
     *
     * <p>Per-id error semantics are preserved deliberately, which is why this re-walks {@code ids}
     * rather than just checking sizes: a caller who passes one id they do not own still gets the
     * 403 naming a transaction, and one that does not exist still gets a 404, exactly as before.
     * Collapsing both into "some of these are not yours" would be a worse answer cheaply obtained.
     */
    private List<Transaction> getOwnedAll(UUID userId, List<UUID> ids) {
        Map<UUID, Transaction> found = transactionRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Transaction::getId, t -> t));
        return ids.stream()
                .map(id -> OwnershipGuard.requireOwned(
                        java.util.Optional.ofNullable(found.get(id)),
                        Transaction::getUserId, userId, "Transaction"))
                .toList();
    }

    private Transaction getOwned(UUID userId, UUID txnId) {
        return OwnershipGuard.requireOwned(
                transactionRepository.findById(txnId), Transaction::getUserId, userId, "Transaction");
    }

    /** The same check AccountService applies -- both now route through {@link OwnershipGuard}
     *  rather than each keeping its own copy. This method survives only as a named shorthand for
     *  the label/getter pair; the security logic itself lives in exactly one place. */
    private Account getOwnedAccount(UUID userId, UUID accountId) {
        return OwnershipGuard.requireOwned(
                accountRepository.findById(accountId), Account::getUserId, userId, "Account");
    }
}
