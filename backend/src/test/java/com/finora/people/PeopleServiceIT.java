package com.finora.people;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.LoanEntry;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.ReportService;
import com.finora.util.CounterpartyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PeopleService's writes (spec §5, §7), against the real database and the real figures. */
class PeopleServiceIT extends AbstractIntegrationTest {

    @Autowired private PeopleService people;
    @Autowired private ReportService reportService;
    @Autowired private com.finora.inflow.InflowKindService inflowKindService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;

    private UUID userId;
    private UUID accountId;
    private Category rent;
    private final YearMonth month = YearMonth.now();

    @BeforeEach
    void setUp() {
        userId = newUser();
        Account a = new Account();
        a.setUserId(userId);
        a.setName("People Savings");
        a.setAccountType(Account.Type.SAVINGS);
        a.setBalance(BigDecimal.valueOf(100000));
        accountId = accountRepository.save(a).getId();
        rent = new Category();
        rent.setUserId(userId);
        rent.setName("Rent");
        rent = categoryRepository.save(rent);
    }

    private UUID newUser() {
        User u = new User();
        u.setEmail("people-service-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("not-used");
        u.setFullName("People Service User");
        return userRepository.save(u).getId();
    }

    private Transaction txn(Transaction.Type type, int amount, String key, UUID categoryId) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setTxnType(type);
        t.setAmount(BigDecimal.valueOf(amount));
        t.setTxnDate(month.atDay(1));
        t.setDescription("UPI " + key);
        t.setCounterpartyKey(key);
        t.setCounterpartyType(CounterpartyType.PERSON);
        t.setCategoryId(categoryId);
        t.setSource(Transaction.Source.MANUAL);
        return transactionRepository.save(t);
    }

    private BigDecimal monthSpend() {
        return reportService.forMonth(userId, month.toString()).expense();
    }

    private static ErrorCode codeOf(Throwable t) { return ((ApiException) t).getCode(); }

    @Test
    void linkDefaultsToCap() {
        Transaction bill = txn(Transaction.Type.EXPENSE, 1000, "vpa:owner.b", rent.getId());
        Transaction credit = txn(Transaction.Type.INCOME, 1500, "vpa:flatmate.a", rent.getId());
        PeopleDtos.ShareDto share = people.link(userId, new PeopleDtos.LinkShareRequest(credit.getId(), bill.getId(), null));
        assertThat(share.amount()).isEqualByComparingTo("1000");
    }

    @Test
    void linkRefusesTheWrongWayRound() {
        Transaction bill = txn(Transaction.Type.EXPENSE, 1000, "vpa:owner.b", rent.getId());
        Transaction credit = txn(Transaction.Type.INCOME, 500, "vpa:flatmate.a", rent.getId());
        assertThatThrownBy(() -> people.link(userId, new PeopleDtos.LinkShareRequest(bill.getId(), credit.getId(), null)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.PEOPLE_WRONG_DIRECTION));
    }

    @Test
    void anotherUsersTransactionIsNotFound() {
        Transaction bill = txn(Transaction.Type.EXPENSE, 1000, "vpa:owner.b", rent.getId());
        Transaction credit = txn(Transaction.Type.INCOME, 500, "vpa:flatmate.a", rent.getId());
        UUID stranger = newUser();
        assertThatThrownBy(() -> people.link(stranger, new PeopleDtos.LinkShareRequest(credit.getId(), bill.getId(), null)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.TXN_NOT_FOUND));
    }

    @Test
    void linkThenUndoRestoresFigures() {
        Transaction bill = txn(Transaction.Type.EXPENSE, 30000, "vpa:owner.b", rent.getId());
        Transaction credit = txn(Transaction.Type.INCOME, 7500, "vpa:flatmate.a", rent.getId());
        BigDecimal before = monthSpend();
        people.link(userId, new PeopleDtos.LinkShareRequest(credit.getId(), bill.getId(), null));
        assertThat(monthSpend()).isEqualByComparingTo("22500");
        people.unlink(userId, credit.getId(), bill.getId());
        assertThat(monthSpend()).isEqualByComparingTo(before);
    }

    @Test
    void entryOnAWeakKeyIsRefused() {
        Transaction credit = txn(Transaction.Type.INCOME, 500, "cut:abc", null);
        assertThatThrownBy(() -> people.addEntry(userId, new PeopleDtos.EntryRequest(credit.getId(), LoanEntry.Direction.PAID_BACK)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.PEOPLE_NOT_ONE_PAYEE));
    }

    @Test
    void lentIsNotSpendingAndSettleCashClosesTheBalance() {
        Transaction lent = txn(Transaction.Type.EXPENSE, 2000, "vpa:friend.k", null);
        BigDecimal before = monthSpend();
        people.addEntry(userId, new PeopleDtos.EntryRequest(lent.getId(), LoanEntry.Direction.LENT));
        assertThat(monthSpend()).isEqualByComparingTo(before.subtract(BigDecimal.valueOf(2000)));
        assertThat(people.people(userId)).extracting(PeopleDtos.PersonDto::personKey).containsExactly("vpa:friend.k");
        people.settle(userId, new PeopleDtos.SettleRequest("vpa:friend.k", PeopleDtos.SettleHow.CASH));
        assertThat(people.people(userId)).isEmpty();
        assertThat(monthSpend()).isEqualByComparingTo(before.subtract(BigDecimal.valueOf(2000)));
    }

    @Test
    void letGoRefilesUnderGiftsAndCountsOnlyWhatNeverCameBack() {
        Transaction lent = txn(Transaction.Type.EXPENSE, 2000, "vpa:friend.k", null);
        Transaction back = txn(Transaction.Type.INCOME, 1500, "vpa:friend.k", null);
        people.addEntry(userId, new PeopleDtos.EntryRequest(lent.getId(), LoanEntry.Direction.LENT));
        people.addEntry(userId, new PeopleDtos.EntryRequest(back.getId(), LoanEntry.Direction.PAID_BACK));
        BigDecimal withoutLoan = monthSpend();
        people.settle(userId, new PeopleDtos.SettleRequest("vpa:friend.k", PeopleDtos.SettleHow.LET_GO));
        Transaction refiled = transactionRepository.findById(lent.getId()).orElseThrow();
        assertThat(categoryRepository.findById(refiled.getCategoryId()).orElseThrow().getName()).isEqualTo("Gifts & Donations");
        assertThat(monthSpend()).isEqualByComparingTo(withoutLoan.add(BigDecimal.valueOf(500)));
        assertThat(people.people(userId)).isEmpty();
    }

    @Autowired private com.finora.repository.LoanEntryRepository loanEntryRepository;
    @Autowired private com.finora.transactions.TransactionService transactionService;

    /** A lent payment filed under Shopping by the engine, lent and then let go. */
    private Transaction letGoOfAShoppingLoan() {
        Category shopping = new Category();
        shopping.setUserId(userId);
        shopping.setName("Shopping");
        shopping = categoryRepository.save(shopping);
        Transaction lent = txn(Transaction.Type.EXPENSE, 700, "vpa:neha.r", shopping.getId());
        lent.setDecisionSource(Transaction.DecisionSource.KEYWORD_MATCH);
        lent.setDecisionConfidence(80);
        lent.setNeedsCategoryReview(true);
        lent = transactionRepository.save(lent);
        people.addEntry(userId, new PeopleDtos.EntryRequest(lent.getId(), LoanEntry.Direction.LENT));
        people.settle(userId, new PeopleDtos.SettleRequest("vpa:neha.r", PeopleDtos.SettleHow.LET_GO));
        assertThat(categoryName(lent.getId())).isEqualTo("Gifts & Donations");
        return lent;
    }

    private String categoryName(UUID txnId) {
        UUID id = transactionRepository.findById(txnId).orElseThrow().getCategoryId();
        return id == null ? null : categoryRepository.findById(id).orElseThrow().getName();
    }

    private UUID entryId(UUID txnId, LoanEntry.Direction direction) {
        return loanEntryRepository.findByUserIdAndTxnId(userId, txnId).stream()
                .filter(e -> e.getDirection() == direction).findFirst().orElseThrow().getId();
    }

    /** Undoing a let-go by removing the loan. Measured before the fix: the payment stayed under Gifts
     *  & Donations, filed by hand. It goes back exactly as the engine had it. */
    @Test
    void removingALetGoLoan_putsThePaymentBackInItsCategory() {
        Transaction lent = letGoOfAShoppingLoan();

        people.deleteEntry(userId, entryId(lent.getId(), LoanEntry.Direction.LENT));

        Transaction back = transactionRepository.findById(lent.getId()).orElseThrow();
        assertThat(categoryName(back.getId())).isEqualTo("Shopping");
        assertThat(back.isCategoryManuallySet()).isFalse();
        assertThat(back.getDecisionSource()).isEqualTo(Transaction.DecisionSource.KEYWORD_MATCH);
        assertThat(back.getDecisionConfidence()).isEqualTo(80);
        assertThat(back.isNeedsCategoryReview()).isTrue();
        assertThat(loanEntryRepository.findByUserIdAndTxnId(userId, lent.getId())).isEmpty();
    }

    /** Removing only the let-go: the loan is open again, and the payment back where it was. */
    @Test
    void removingTheLetGoItself_putsThePaymentBackAndReopensTheLoan() {
        Transaction lent = letGoOfAShoppingLoan();

        people.deleteEntry(userId, entryId(lent.getId(), LoanEntry.Direction.LET_GO));

        assertThat(categoryName(lent.getId())).isEqualTo("Shopping");
        assertThat(people.people(userId)).singleElement()
                .satisfies(p -> assertThat(p.owedToUser()).isEqualByComparingTo("700"));
    }

    /** A category the user chose after the let-go is theirs: the undo leaves it. */
    @Test
    void undoingALetGo_keepsACategoryTheUserChoseSince() {
        Transaction lent = letGoOfAShoppingLoan();
        transactionService.updateCategory(userId, lent.getId(), "Rent");

        people.deleteEntry(userId, entryId(lent.getId(), LoanEntry.Direction.LENT));

        assertThat(categoryName(lent.getId())).isEqualTo("Rent");
    }

    /** The kept category deleted since: nothing to go back to, so the payment stays a gift. */
    @Test
    void undoingALetGo_leavesTheGiftWhenTheOldCategoryIsGone() {
        Transaction lent = letGoOfAShoppingLoan();
        Category shopping = categoryRepository.findByUserId(userId).stream()
                .filter(c -> c.getName().equals("Shopping")).findFirst().orElseThrow();
        UUID giftId = transactionRepository.findById(lent.getId()).orElseThrow().getCategoryId();
        // Moving the row off it first, the way deleting a category in the app does, would also change
        // the row; this keeps the row a gift and only takes the old category away.
        categoryRepository.delete(shopping);

        people.deleteEntry(userId, entryId(lent.getId(), LoanEntry.Direction.LENT));

        assertThat(transactionRepository.findById(lent.getId()).orElseThrow().getCategoryId()).isEqualTo(giftId);
    }

    @Autowired private com.finora.repository.CategoryRuleRepository categoryRuleRepository;

    /** A payment filed by one of the user's rules, let go, and the rule deleted before the undo. The
     *  kept decision names a rule that no longer exists (transactions.decision_rule_id references
     *  category_rules): the undo must still go through, without the rule. */
    @Test
    void undoingALetGo_afterItsRuleWasDeleted_stillGoesBack() {
        Category shopping = new Category();
        shopping.setUserId(userId);
        shopping.setName("Shopping");
        shopping = categoryRepository.save(shopping);
        com.finora.entity.CategoryRule rule = new com.finora.entity.CategoryRule();
        rule.setUserId(userId);
        rule.setScope(com.finora.entity.CategoryRule.Scope.USER);
        rule.setField(com.finora.entity.CategoryRule.Field.DESCRIPTION);
        rule.setOperator(com.finora.entity.CategoryRule.Operator.CONTAINS);
        rule.setComparisonValue("loan");
        rule.setActionType(com.finora.entity.CategoryRule.ActionType.ASSIGN_CATEGORY);
        rule.setActionValue("Shopping");
        rule.setPriority(100);
        rule.setEnabled(true);
        rule = categoryRuleRepository.save(rule);
        Transaction lent = txn(Transaction.Type.EXPENSE, 700, "vpa:neha.r", shopping.getId());
        lent.setDecisionSource(Transaction.DecisionSource.USER_RULE);
        lent.setDecisionRuleId(rule.getId());
        lent = transactionRepository.save(lent);
        people.addEntry(userId, new PeopleDtos.EntryRequest(lent.getId(), LoanEntry.Direction.LENT));
        people.settle(userId, new PeopleDtos.SettleRequest("vpa:neha.r", PeopleDtos.SettleHow.LET_GO));
        categoryRuleRepository.delete(rule);

        people.deleteEntry(userId, entryId(lent.getId(), LoanEntry.Direction.LENT));

        Transaction back = transactionRepository.findById(lent.getId()).orElseThrow();
        assertThat(categoryName(back.getId())).isEqualTo("Shopping");
        assertThat(back.getDecisionRuleId()).isNull();
        assertThat(loanEntryRepository.findByUserIdAndTxnId(userId, lent.getId())).isEmpty();
    }

    /** A payment with no category before the let-go goes back to having none. */
    @Test
    void undoingALetGo_ofAnUncategorisedPayment_clearsTheCategoryAgain() {
        Transaction lent = txn(Transaction.Type.EXPENSE, 400, "vpa:kiran.p", null);
        people.addEntry(userId, new PeopleDtos.EntryRequest(lent.getId(), LoanEntry.Direction.LENT));
        people.settle(userId, new PeopleDtos.SettleRequest("vpa:kiran.p", PeopleDtos.SettleHow.LET_GO));
        assertThat(categoryName(lent.getId())).isEqualTo("Gifts & Donations");

        people.deleteEntry(userId, entryId(lent.getId(), LoanEntry.Direction.LENT));

        assertThat(transactionRepository.findById(lent.getId()).orElseThrow().getCategoryId()).isNull();
    }

    @Test
    void settlingWhenNothingIsOwedIsRefused() {
        assertThatThrownBy(() -> people.settle(userId, new PeopleDtos.SettleRequest("vpa:friend.k", PeopleDtos.SettleHow.CASH)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.PEOPLE_NOTHING_OWED));
    }

    @Test
    void splitRuleRefusesAWeakSender() {
        Transaction bill = txn(Transaction.Type.EXPENSE, 30000, "vpa:owner.b", rent.getId());
        Transaction credit = txn(Transaction.Type.INCOME, 7500, "cut:abc", rent.getId());
        assertThatThrownBy(() -> people.addSplitRule(userId, new PeopleDtos.SplitRuleRequest(credit.getId(), bill.getId())))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.PEOPLE_NOT_ONE_PAYEE));
    }

    @Test
    void splitRuleLinksAndRemovingItUndoes() {
        Transaction bill = txn(Transaction.Type.EXPENSE, 30000, "vpa:owner.b", rent.getId());
        Transaction credit = txn(Transaction.Type.INCOME, 7500, "vpa:flatmate.a", rent.getId());
        PeopleDtos.SplitRuleDto rule = people.addSplitRule(userId, new PeopleDtos.SplitRuleRequest(credit.getId(), bill.getId()));
        assertThat(rule.billPayeeKey()).isEqualTo("vpa:owner.b");
        assertThat(monthSpend()).isEqualByComparingTo("22500");
        assertThat(people.billShares(userId, bill.getId())).singleElement()
                .satisfies(s -> assertThat(s.fromRule()).isTrue());
        people.removeSplitRule(userId, rule.id());
        assertThat(monthSpend()).isEqualByComparingTo("30000");
    }

    // ---- one answer per payment (review 2026-10-19) ----

    @Test
    void answeringAgainReplacesThePreviousAnswer() {
        Transaction out = txn(Transaction.Type.EXPENSE, 1000, "vpa:friend.k", null);
        people.addEntry(userId, new PeopleDtos.EntryRequest(out.getId(), LoanEntry.Direction.LENT));
        people.addEntry(userId, new PeopleDtos.EntryRequest(out.getId(), LoanEntry.Direction.REPAID));
        assertThat(people.person(userId, "vpa:friend.k").timeline()).singleElement()
                .satisfies(i -> assertThat(i.kind()).isEqualTo("REPAID"));
        assertThat(people.person(userId, "vpa:friend.k").owedToUser()).isEqualByComparingTo("1000");
    }

    @Test
    void linkingAPaidBackCreditAsAShareDropsThePaidBack() {
        Transaction lent = txn(Transaction.Type.EXPENSE, 1000, "vpa:friend.k", null);
        people.addEntry(userId, new PeopleDtos.EntryRequest(lent.getId(), LoanEntry.Direction.LENT));
        Transaction credit = txn(Transaction.Type.INCOME, 1000, "vpa:friend.k", null);
        people.addEntry(userId, new PeopleDtos.EntryRequest(credit.getId(), LoanEntry.Direction.PAID_BACK));
        Transaction bill = txn(Transaction.Type.EXPENSE, 4000, "vpa:owner.b", rent.getId());
        people.link(userId, new PeopleDtos.LinkShareRequest(credit.getId(), bill.getId(), null));
        // The credit is a share now, not a repayment: the friend still owes the 1000.
        assertThat(people.person(userId, "vpa:friend.k").owedToUser()).isEqualByComparingTo("1000");
        assertThat(people.billShares(userId, bill.getId())).hasSize(1);
    }

    @Test
    void markingAShareCreditPaidBackDropsTheShare() {
        Transaction bill = txn(Transaction.Type.EXPENSE, 4000, "vpa:owner.b", rent.getId());
        Transaction credit = txn(Transaction.Type.INCOME, 1000, "vpa:friend.k", null);
        people.link(userId, new PeopleDtos.LinkShareRequest(credit.getId(), bill.getId(), null));
        people.addEntry(userId, new PeopleDtos.EntryRequest(credit.getId(), LoanEntry.Direction.PAID_BACK));
        assertThat(people.billShares(userId, bill.getId())).isEmpty();
        assertThat(monthSpend()).isEqualByComparingTo("4000");
    }

    @Test
    void lendingABillThatHasSharesIsRefused() {
        Transaction bill = txn(Transaction.Type.EXPENSE, 4000, "vpa:owner.b", rent.getId());
        Transaction credit = txn(Transaction.Type.INCOME, 1000, "vpa:flatmate.a", rent.getId());
        people.link(userId, new PeopleDtos.LinkShareRequest(credit.getId(), bill.getId(), null));
        assertThatThrownBy(() -> people.addEntry(userId, new PeopleDtos.EntryRequest(bill.getId(), LoanEntry.Direction.LENT)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.PEOPLE_WRONG_DIRECTION));
    }

    /** A transfer, refund or reversal is not spending: a share against it would take the credit out of
     *  every figure while lowering nothing, and a loan answer on it would move money that was never spent. */
    @Test
    void aPairedRowTakesNoShareAndNoLoanAnswer() {
        Transaction transfer = txn(Transaction.Type.EXPENSE, 4000, "vpa:owner.b", rent.getId());
        transfer.setReconciliationStatus(Transaction.ReconciliationStatus.TRANSFER);
        transactionRepository.save(transfer);
        Transaction credit = txn(Transaction.Type.INCOME, 1000, "vpa:flatmate.a", rent.getId());
        assertThatThrownBy(() -> people.link(userId, new PeopleDtos.LinkShareRequest(credit.getId(), transfer.getId(), null)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.PEOPLE_WRONG_DIRECTION));
        assertThatThrownBy(() -> people.addEntry(userId, new PeopleDtos.EntryRequest(transfer.getId(), LoanEntry.Direction.LENT)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.PEOPLE_WRONG_DIRECTION));

        Transaction refund = txn(Transaction.Type.INCOME, 1000, "vpa:flatmate.a", rent.getId());
        refund.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);
        transactionRepository.save(refund);
        Transaction bill = txn(Transaction.Type.EXPENSE, 4000, "vpa:owner.b", rent.getId());
        assertThatThrownBy(() -> people.link(userId, new PeopleDtos.LinkShareRequest(refund.getId(), bill.getId(), null)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.PEOPLE_WRONG_DIRECTION));
        assertThatThrownBy(() -> people.addEntry(userId, new PeopleDtos.EntryRequest(refund.getId(), LoanEntry.Direction.PAID_BACK)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.PEOPLE_WRONG_DIRECTION));
        assertThat(people.billShares(userId, transfer.getId())).isEmpty();
    }

    @Test
    void lettingGoAgainAfterAPartLetGoNeverFails() {
        Transaction first = txn(Transaction.Type.EXPENSE, 2000, "vpa:friend.k", null);
        people.addEntry(userId, new PeopleDtos.EntryRequest(first.getId(), LoanEntry.Direction.LENT));
        Transaction back = txn(Transaction.Type.INCOME, 1500, "vpa:friend.k", null);
        people.addEntry(userId, new PeopleDtos.EntryRequest(back.getId(), LoanEntry.Direction.PAID_BACK));
        people.settle(userId, new PeopleDtos.SettleRequest("vpa:friend.k", PeopleDtos.SettleHow.LET_GO));
        Transaction second = txn(Transaction.Type.EXPENSE, 300, "vpa:friend.k", null);
        people.addEntry(userId, new PeopleDtos.EntryRequest(second.getId(), LoanEntry.Direction.LENT));
        Transaction repaid = txn(Transaction.Type.EXPENSE, 900, "vpa:friend.k", null);
        people.addEntry(userId, new PeopleDtos.EntryRequest(repaid.getId(), LoanEntry.Direction.REPAID));
        people.settle(userId, new PeopleDtos.SettleRequest("vpa:friend.k", PeopleDtos.SettleHow.LET_GO));
        assertThat(people.people(userId)).isEmpty();
    }

    // ---- the leftover of a credit bigger than its bill (fix everything, 2026-10-19) ----

    @Test
    void leftoverPaidBackKeepsTheShareAndSettlesTheRest() {
        Transaction lent = txn(Transaction.Type.EXPENSE, 500, "vpa:friend.k", null);
        people.addEntry(userId, new PeopleDtos.EntryRequest(lent.getId(), LoanEntry.Direction.LENT, null));
        Transaction bill = txn(Transaction.Type.EXPENSE, 1000, "vpa:owner.b", rent.getId());
        Transaction credit = txn(Transaction.Type.INCOME, 1500, "vpa:friend.k", rent.getId());
        people.link(userId, new PeopleDtos.LinkShareRequest(credit.getId(), bill.getId(), null));
        people.addEntry(userId, new PeopleDtos.EntryRequest(credit.getId(), LoanEntry.Direction.PAID_BACK, new BigDecimal("500")));
        assertThat(people.billShares(userId, bill.getId())).singleElement()
                .satisfies(s -> assertThat(s.amount()).isEqualByComparingTo("1000"));
        assertThat(people.people(userId)).isEmpty();
    }

    @Test
    void aLeftoverAnswerAboveWhatIsLeftIsRefused() {
        Transaction bill = txn(Transaction.Type.EXPENSE, 1000, "vpa:owner.b", rent.getId());
        Transaction credit = txn(Transaction.Type.INCOME, 1500, "vpa:friend.k", rent.getId());
        people.link(userId, new PeopleDtos.LinkShareRequest(credit.getId(), bill.getId(), null));
        assertThatThrownBy(() -> people.addEntry(userId,
                new PeopleDtos.EntryRequest(credit.getId(), LoanEntry.Direction.PAID_BACK, new BigDecimal("600"))))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.PEOPLE_MORE_THAN_LEFT));
    }

    @Test
    void theLeftoverAnswerSurvivesRelinkingAndCountsAgainstTheCredit() {
        Transaction bill = txn(Transaction.Type.EXPENSE, 1000, "vpa:owner.b", rent.getId());
        Transaction other = txn(Transaction.Type.EXPENSE, 800, "vpa:shop.x", rent.getId());
        Transaction credit = txn(Transaction.Type.INCOME, 1500, "vpa:friend.k", rent.getId());
        people.link(userId, new PeopleDtos.LinkShareRequest(credit.getId(), bill.getId(), null));
        people.addEntry(userId, new PeopleDtos.EntryRequest(credit.getId(), LoanEntry.Direction.PAID_BACK, new BigDecimal("500")));
        // A second tap on the same link keeps the leftover answer...
        people.link(userId, new PeopleDtos.LinkShareRequest(credit.getId(), bill.getId(), null));
        assertThat(people.person(userId, "vpa:friend.k").timeline()).anySatisfy(i -> {
            assertThat(i.kind()).isEqualTo("PAID_BACK");
            assertThat(i.amount()).isEqualByComparingTo("500");
        });
        // ...and nothing of the credit is left for another bill: 1000 shared + 500 answered.
        assertThatThrownBy(() -> people.link(userId, new PeopleDtos.LinkShareRequest(credit.getId(), other.getId(), null)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.PEOPLE_NOTHING_LEFT));
    }

    @Test
    void aPartialAnswerNeedsSharesBesideIt() {
        Transaction credit = txn(Transaction.Type.INCOME, 1500, "vpa:friend.k", null);
        assertThatThrownBy(() -> people.addEntry(userId,
                new PeopleDtos.EntryRequest(credit.getId(), LoanEntry.Direction.PAID_BACK, new BigDecimal("500"))))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.PEOPLE_WRONG_DIRECTION));
    }

    @Test
    void unlinkingTheLastShareDropsTheLeftoverAnswer() {
        Transaction bill = txn(Transaction.Type.EXPENSE, 1000, "vpa:owner.b", rent.getId());
        Transaction credit = txn(Transaction.Type.INCOME, 1500, "vpa:friend.k", rent.getId());
        people.link(userId, new PeopleDtos.LinkShareRequest(credit.getId(), bill.getId(), null));
        people.addEntry(userId, new PeopleDtos.EntryRequest(credit.getId(), LoanEntry.Direction.PAID_BACK, new BigDecimal("500")));
        people.unlink(userId, credit.getId(), bill.getId());
        assertThat(people.person(userId, "vpa:friend.k").timeline()).isEmpty();
    }

    // ---- a double tap never fails (fix everything, 2026-10-19) ----

    @Test
    void concurrentTapsNeverFail() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 15; round++) {
                Transaction bill = txn(Transaction.Type.EXPENSE, 1000, "vpa:owner.b", rent.getId());
                Transaction credit = txn(Transaction.Type.INCOME, 400, "vpa:flatmate.a", rent.getId());
                Transaction lent = txn(Transaction.Type.EXPENSE, 300, "vpa:friend.k", null);
                java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
                java.util.List<java.util.concurrent.Future<?>> taps = new java.util.ArrayList<>();
                for (int t = 0; t < 2; t++) {
                    taps.add(pool.submit(() -> {
                        start.await();
                        people.link(userId, new PeopleDtos.LinkShareRequest(credit.getId(), bill.getId(), null));
                        people.addEntry(userId, new PeopleDtos.EntryRequest(lent.getId(), LoanEntry.Direction.LENT, null));
                        people.setPersonSettings(userId, new PeopleDtos.PersonSettingsRequest("vpa:friend.k", false));
                        return null;
                    }));
                }
                for (var tap : taps) tap.get();
                assertThat(people.billShares(userId, bill.getId())).hasSize(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Two "Paid in cash" taps at once settle the balance once, never twice into a negative one. */
    @Test
    void concurrentSettlesSettleOnce() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                String key = "vpa:friend." + round;
                Transaction lent = txn(Transaction.Type.EXPENSE, 1000, key, null);
                people.addEntry(userId, new PeopleDtos.EntryRequest(lent.getId(), LoanEntry.Direction.LENT));
                java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
                java.util.List<java.util.concurrent.Future<?>> taps = new java.util.ArrayList<>();
                for (int t = 0; t < 2; t++) {
                    taps.add(pool.submit(() -> {
                        start.await();
                        try {
                            people.settle(userId, new PeopleDtos.SettleRequest(key, PeopleDtos.SettleHow.CASH));
                        } catch (ApiException alreadySettled) {
                            assertThat(alreadySettled.getCode()).isEqualTo(ErrorCode.PEOPLE_NOTHING_OWED);
                        }
                        return null;
                    }));
                }
                for (var tap : taps) tap.get();
                assertThat(people.people(userId)).as("round " + round).noneMatch(p -> p.personKey().equals(key));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void countsAsNamesTheShareNotTheSendersKind() {
        Transaction bill = txn(Transaction.Type.EXPENSE, 4000, "vpa:owner.b", rent.getId());
        Transaction credit = txn(Transaction.Type.INCOME, 1000, "vpa:flatmate.a", rent.getId());
        UUID incomeKind = inflowKindService.list(userId).stream()
                .filter(k -> "INCOME".equals(k.builtIn())).findFirst().orElseThrow().id();
        inflowKindService.setChoice(userId, credit.getId(),
                new com.finora.inflow.InflowDtos.SetChoiceRequest(incomeKind, com.finora.service.InflowChoices.Scope.SENDER));
        people.link(userId, new PeopleDtos.LinkShareRequest(credit.getId(), bill.getId(), null));
        var countsAs = inflowKindService.countsAs(userId, credit.getId());
        assertThat(countsAs.flowReason()).isEqualTo("SHARE_OF_BILL");
        assertThat(countsAs.kind()).isNull();
        assertThat(countsAs.summary()).contains("share");
    }

    @Test
    void personDetailHasTimelineAndFamilySwitch() {
        Transaction lent = txn(Transaction.Type.EXPENSE, 2000, "vpa:friend.k", null);
        people.addEntry(userId, new PeopleDtos.EntryRequest(lent.getId(), LoanEntry.Direction.LENT));
        people.setPersonSettings(userId, new PeopleDtos.PersonSettingsRequest("vpa:friend.k", true));
        PeopleDtos.PersonDetailDto detail = people.person(userId, "vpa:friend.k");
        assertThat(detail.family()).isTrue();
        assertThat(detail.owedToUser()).isEqualByComparingTo("2000");
        assertThat(detail.timeline()).singleElement().satisfies(i -> assertThat(i.kind()).isEqualTo("LENT"));
    }

    @Test
    void ownUpiIdIsSaved() {
        people.setPreferences(userId, new PeopleDtos.PreferencesRequest("someone@upi"));
        assertThat(people.preferences(userId).ownUpiId()).isEqualTo("someone@upi");
    }
}
