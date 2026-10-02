package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.entity.CategoryRule;
import com.finora.entity.ImportSession;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.CategoryRuleRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.transactions.TransactionDto;
import com.finora.transactions.TransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A user's own rules and choices beat a GLOBAL MARK_INVESTMENT rule, through the real write paths
 * against real Postgres: a manually created transaction and a confirmed import.
 *
 * <p>Why it exists: before CategorizationService.investmentRuleToApply, every matching
 * MARK_INVESTMENT rule was applied in turn, global ones last. Measured through
 * TransactionService.create: a global rule turned a category the user typed ("Groceries") and one
 * their own ASSIGN_CATEGORY rule set ("Dining") into "Investments", and replaced the category
 * their own MARK_INVESTMENT rule chose with the global rule's. The admin Global Rules page and
 * RuleEngineService both promise the opposite -- a user's own rules run first.
 *
 * <p>Each test uses its own random description token, so the global rules it inserts match only
 * its own rows; they are deleted afterwards because category_rules is shared by every IT.
 */
class InvestmentRulePrecedenceIT extends AbstractIntegrationTest {

    @Autowired private TransactionService transactionService;
    @Autowired private ImportService importService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private CategoryRuleRepository ruleRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;

    private final List<UUID> createdUserIds = new ArrayList<>();
    private final List<UUID> createdRuleIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        ruleRepository.deleteAllById(createdRuleIds);
        createdRuleIds.clear();
        if (createdUserIds.isEmpty()) return;
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> createdUserIds.contains(e.getUserId()))
                .toList());
        createdUserIds.clear();
    }

    private record Fixture(UUID userId, UUID accountId, String token) {}

    private Fixture fixture() {
        User user = new User();
        user.setEmail("investment-precedence-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Investment Precedence IT User");
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        createdUserIds.add(user.getId());
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("100000.00"));
        account = accountRepository.save(account);
        String token = "INVPREC" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return new Fixture(user.getId(), account.getId(), token);
    }

    /** userId null = a GLOBAL rule. */
    private CategoryRule rule(UUID userId, CategoryRule.ActionType type, String value, String token) {
        CategoryRule r = new CategoryRule();
        r.setUserId(userId);
        r.setScope(userId == null ? CategoryRule.Scope.GLOBAL : CategoryRule.Scope.USER);
        r.setField(CategoryRule.Field.DESCRIPTION);
        r.setOperator(CategoryRule.Operator.CONTAINS);
        r.setComparisonValue(token);
        r.setActionType(type);
        r.setActionValue(value);
        r.setPriority(100);
        r.setEnabled(true);
        CategoryRule saved = ruleRepository.save(r);
        createdRuleIds.add(saved.getId());
        return saved;
    }

    private TransactionDto create(Fixture f, String categoryName) {
        return transactionService.create(f.userId(), new TransactionDto.CreateRequest(
                f.accountId(), categoryName, LocalDate.of(2026, 7, 1), "SIP " + f.token(),
                new BigDecimal("500.00"), "EXPENSE", null));
    }

    @Test
    void create_aGlobalInvestmentRule_doesNotReplaceTheCategoryTheUsersOwnRuleSet() {
        Fixture f = fixture();
        rule(f.userId(), CategoryRule.ActionType.ASSIGN_CATEGORY, "Dining", f.token());
        rule(null, CategoryRule.ActionType.MARK_INVESTMENT, null, f.token());

        assertThat(create(f, null).categoryName()).isEqualTo("Dining");
    }

    @Test
    void create_aGlobalInvestmentRule_doesNotReplaceACategoryTheUserTyped() {
        Fixture f = fixture();
        rule(null, CategoryRule.ActionType.MARK_INVESTMENT, null, f.token());

        TransactionDto dto = create(f, "Groceries");

        assertThat(dto.categoryName()).isEqualTo("Groceries");
        assertThat(transactionRepository.findById(dto.id()).orElseThrow().getCategoryId())
                .isEqualTo(dto.categoryId());
    }

    @Test
    void create_theUsersOwnInvestmentRule_beatsAGlobalOne() {
        Fixture f = fixture();
        rule(f.userId(), CategoryRule.ActionType.MARK_INVESTMENT, "SIP Mine", f.token());
        CategoryRule global = rule(null, CategoryRule.ActionType.MARK_INVESTMENT, "SIP Global", f.token());

        assertThat(create(f, null).categoryName()).isEqualTo("SIP Mine");
        // The global rule did nothing, so it neither created its category nor counted a match.
        assertThat(categoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc(f.userId(), "SIP Global")).isEmpty();
        assertThat(ruleRepository.findById(global.getId()).orElseThrow().getMatchCount()).isZero();
    }

    @Test
    void create_aGlobalInvestmentRule_stillReplacesAnEngineGuess() {
        Fixture f = fixture();
        rule(null, CategoryRule.ActionType.MARK_INVESTMENT, null, f.token());

        assertThat(create(f, null).categoryName()).isEqualTo("Investments");
    }

    @Test
    void create_globalTagAndTransferRules_stillApplyAlongsideTheUsersOwnCategoryRule() {
        Fixture f = fixture();
        rule(f.userId(), CategoryRule.ActionType.ASSIGN_CATEGORY, "Dining", f.token());
        rule(null, CategoryRule.ActionType.MARK_INVESTMENT, null, f.token());
        rule(null, CategoryRule.ActionType.ADD_TAG, "sip", f.token());
        rule(null, CategoryRule.ActionType.MARK_TRANSFER, null, f.token());

        TransactionDto dto = create(f, null);

        assertThat(dto.categoryName()).isEqualTo("Dining");
        assertThat(dto.tags()).containsExactly("sip");
        assertThat(dto.reconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.TRANSFER);
    }

    // --- Import confirm: the same guard, reached through ImportService.confirmSession ---

    private StagedRow staged(String description, String suggestedCategory, String source, UUID ruleId, int rowPosition) {
        return new StagedRow(LocalDate.of(2026, 7, 1), description, new BigDecimal("500.00"), "EXPENSE",
                suggestedCategory, source, ruleId, false, null, null, null, RowKind.TRANSACTION, null, null, null, 90)
                .withRowPosition(rowPosition);
    }

    private ConfirmedRow confirmed(StagedRow r, String category) {
        return new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(), category, true,
                r.categorySource(), r.ruleId(), false, null, null, false, r.categoryConfidence(), r.rowPosition());
    }

    @Test
    void importConfirm_aGlobalInvestmentRule_onlyReplacesTheRowWhoseCategoryWasAnEngineGuess() {
        Fixture f = fixture();
        CategoryRule own = rule(f.userId(), CategoryRule.ActionType.ASSIGN_CATEGORY, "Dining", f.token());
        rule(null, CategoryRule.ActionType.MARK_INVESTMENT, null, f.token());

        StagedRow byOwnRule = staged("SIP " + f.token() + " A", "Dining", "user_rule", own.getId(), 0);
        StagedRow changedOnReview = staged("SIP " + f.token() + " B", "Shopping", "rule", null, 1);
        StagedRow guessed = staged("SIP " + f.token() + " C", "Shopping", "rule", null, 2);
        byte[] file = ("Date,Description,Amount,Type\n"
                + "2026-07-01,SIP " + f.token() + " A,500.00,DEBIT\n"
                + "2026-07-01,SIP " + f.token() + " B,500.00,DEBIT\n"
                + "2026-07-01,SIP " + f.token() + " C,500.00,DEBIT\n").getBytes(StandardCharsets.UTF_8);
        ImportSession session = importSessionService.createSession(
                f.userId(), "statement.csv", file, List.of(byOwnRule, changedOnReview, guessed), null);

        importService.confirmSession(f.userId(), new ConfirmRequest(session.getId(),
                List.of(confirmed(byOwnRule, "Dining"), confirmed(changedOnReview, "Groceries"),
                        confirmed(guessed, "Shopping")),
                f.accountId(), null, null, null, null));

        List<Transaction> rows = transactionRepository.findByUserId(f.userId());
        assertThat(categoryOf(rows, f, " A")).isEqualTo("Dining");
        assertThat(categoryOf(rows, f, " B")).isEqualTo("Groceries");
        assertThat(categoryOf(rows, f, " C")).isEqualTo("Investments");
    }

    private String categoryOf(List<Transaction> rows, Fixture f, String suffix) {
        Transaction t = rows.stream().filter(r -> ("SIP " + f.token() + suffix).equals(r.getDescription()))
                .findFirst().orElseThrow();
        return categoryRepository.findById(t.getCategoryId()).orElseThrow().getName();
    }
}
