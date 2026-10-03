package com.finora.transactions;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Apply to all similar" through the real service and a real database: which rows the payee lookup
 * reaches is a query, so only Postgres can show it stays inside one user, one payee, one direction,
 * and skips deleted rows.
 */
class ApplyCategoryToSimilarIT extends AbstractIntegrationTest {

    private static final String PAYEE = "vpa:apply-similar-it-metro";

    @Autowired private TransactionService transactionService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;

    private record Owner(User user, Account account) {}

    private Owner owner() {
        User user = new User();
        user.setEmail("apply-similar-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Apply Similar IT User");
        user.setPhoneVerified(true);
        User saved = userRepository.save(user);
        Account account = new Account();
        account.setUserId(saved.getId());
        account.setName("Apply Similar IT Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("10000.00"));
        return new Owner(saved, accountRepository.save(account));
    }

    private Transaction row(Owner o, String key, Transaction.Type type) {
        Transaction t = new Transaction();
        t.setUserId(o.user().getId());
        t.setAccountId(o.account().getId());
        t.setTxnDate(LocalDate.of(2026, 7, 10));
        t.setAmount(new BigDecimal("30.00"));
        t.setTxnType(type);
        t.setDescription("UPI/DR/111111111111/METRO/HDFC/metro@hdfcbank/fare");
        t.setCounterpartyKey(key);
        t.setNeedsCategoryReview(true);
        return transactionRepository.save(t);
    }

    private Transaction reload(UUID id) {
        return transactionRepository.findById(id).orElseThrow();
    }

    @Test
    void similar_reachesOnlyThisUsersRowsFromThePayee_inTheSameDirection() {
        Owner me = owner();
        Owner someoneElse = owner();
        Transaction chosen = row(me, PAYEE, Transaction.Type.EXPENSE);
        Transaction sameFare = row(me, PAYEE, Transaction.Type.EXPENSE);
        Transaction refundFromIt = row(me, PAYEE, Transaction.Type.INCOME);
        Transaction otherPayee = row(me, "vpa:apply-similar-it-shop", Transaction.Type.EXPENSE);
        Transaction notMine = row(someoneElse, PAYEE, Transaction.Type.EXPENSE);
        Transaction deleted = row(me, PAYEE, Transaction.Type.EXPENSE);
        transactionRepository.delete(deleted);

        assertThat(transactionService.similarSummary(me.user().getId(), chosen.getId()))
                .isEqualTo(new TransactionDto.SimilarSummary(1, 0));

        transactionService.updateCategory(me.user().getId(), chosen.getId(), "Travel",
                TransactionDto.CategoryScope.SIMILAR);

        UUID travel = reload(chosen.getId()).getCategoryId();
        assertThat(travel).isNotNull();
        Transaction fare = reload(sameFare.getId());
        assertThat(fare.getCategoryId()).isEqualTo(travel);
        assertThat(fare.isNeedsCategoryReview()).isFalse();
        assertThat(fare.isCategoryManuallySet()).isTrue();
        assertThat(reload(refundFromIt.getId()).getCategoryId()).isNotEqualTo(travel);
        assertThat(reload(otherPayee.getId()).getCategoryId()).isNotEqualTo(travel);
        assertThat(reload(notMine.getId()).getCategoryId()).isNotEqualTo(travel);
        assertThat(reload(notMine.getId()).isNeedsCategoryReview()).isTrue();
    }

    @Test
    void similar_leavesADeletedAccountsRowsAlone() {
        // Deleting an account keeps its transactions' deleted_at unset (AccountService.delete), so
        // only the account lookup keeps them out of the question and the change.
        Owner me = owner();
        Account closed = new Account();
        closed.setUserId(me.user().getId());
        closed.setName("Apply Similar IT Closed Account");
        closed.setAccountType(Account.Type.SAVINGS);
        closed.setBalance(BigDecimal.ZERO);
        closed = accountRepository.save(closed);
        Transaction chosen = row(me, PAYEE, Transaction.Type.EXPENSE);
        Transaction onClosed = row(new Owner(me.user(), closed), PAYEE, Transaction.Type.EXPENSE);
        accountRepository.delete(closed);

        assertThat(transactionService.similarSummary(me.user().getId(), chosen.getId()))
                .isEqualTo(new TransactionDto.SimilarSummary(0, 0));
        transactionService.updateCategory(me.user().getId(), chosen.getId(), "Travel",
                TransactionDto.CategoryScope.SIMILAR);

        Transaction stillThere = reload(onClosed.getId());
        assertThat(stillThere.getCategoryId()).isNotEqualTo(reload(chosen.getId()).getCategoryId());
        assertThat(stillThere.isNeedsCategoryReview()).isTrue();
    }

    @Test
    void similar_doesNotReachThroughAMaskedUpiId() {
        Owner me = owner();
        Transaction chosen = row(me, "masked:.payu@shopcobk", Transaction.Type.EXPENSE);
        Transaction otherShop = row(me, "masked:.payu@shopcobk", Transaction.Type.EXPENSE);

        assertThat(transactionService.similarSummary(me.user().getId(), chosen.getId()))
                .isEqualTo(new TransactionDto.SimilarSummary(0, 0));
        transactionService.updateCategory(me.user().getId(), chosen.getId(), "Groceries",
                TransactionDto.CategoryScope.SIMILAR);

        assertThat(reload(otherShop.getId()).getCategoryId()).isNotEqualTo(reload(chosen.getId()).getCategoryId());
    }

    @Test
    void similar_keepsACategoryTheUserSetByHand_andOnlyThisLeavesTheOthersAlone() {
        Owner me = owner();
        Transaction first = row(me, PAYEE, Transaction.Type.EXPENSE);
        Transaction second = row(me, PAYEE, Transaction.Type.EXPENSE);
        Transaction third = row(me, PAYEE, Transaction.Type.EXPENSE);

        transactionService.updateCategory(me.user().getId(), second.getId(), "Dining",
                TransactionDto.CategoryScope.ONLY_THIS);
        UUID dining = reload(second.getId()).getCategoryId();
        assertThat(reload(third.getId()).getCategoryId()).isNotEqualTo(dining);
        assertThat(reload(third.getId()).isNeedsCategoryReview()).isTrue();

        assertThat(transactionService.similarSummary(me.user().getId(), first.getId()))
                .isEqualTo(new TransactionDto.SimilarSummary(1, 1));

        transactionService.updateCategory(me.user().getId(), first.getId(), "Travel",
                TransactionDto.CategoryScope.SIMILAR);

        UUID travel = reload(first.getId()).getCategoryId();
        assertThat(reload(third.getId()).getCategoryId()).isEqualTo(travel);
        assertThat(reload(second.getId()).getCategoryId()).isEqualTo(dining);
    }
}
