package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.util.CounterpartyType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link TransactionRepository#findPersonPaymentDescriptions} against a real Postgres: only this
 *  user's person payments, newest first, capped by the page, soft-deleted rows excluded. */
class PersonPaymentDescriptionsIT extends AbstractIntegrationTest {

    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;

    @Test
    @Transactional
    void returnsOnlyThisUsersPersonPayments_newestFirst_capped_andNotDeletedOnes() {
        UUID userId = newUser();
        UUID otherUserId = newUser();
        save(userId, "UPI-RAVI KUMAR-old@ybl-UPI", CounterpartyType.PERSON, LocalDate.of(2026, 7, 1));
        save(userId, "UPI-PRIYA SHARMA-new@ybl-UPI", CounterpartyType.PERSON, LocalDate.of(2026, 8, 1));
        save(userId, "UPI-ZOMATO-zomato@hdfcbank-UPI", CounterpartyType.BUSINESS, LocalDate.of(2026, 8, 2));
        save(otherUserId, "UPI-SOMEONE ELSE-x@ybl-UPI", CounterpartyType.PERSON, LocalDate.of(2026, 8, 3));
        Transaction deleted = save(userId, "UPI-GONE PERSON-g@ybl-UPI", CounterpartyType.PERSON, LocalDate.of(2026, 8, 4));
        transactionRepository.delete(deleted);

        assertThat(transactionRepository.findPersonPaymentDescriptions(userId, PageRequest.of(0, 10)))
                .containsExactly("UPI-PRIYA SHARMA-new@ybl-UPI", "UPI-RAVI KUMAR-old@ybl-UPI");
        assertThat(transactionRepository.findPersonPaymentDescriptions(userId, PageRequest.of(0, 1)))
                .containsExactly("UPI-PRIYA SHARMA-new@ybl-UPI");
    }

    private UUID newUser() {
        User user = new User();
        user.setEmail("person-payments-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Test User");
        return userRepository.save(user).getId();
    }

    private Transaction save(UUID userId, String description, CounterpartyType type, LocalDate date) {
        Account account = new Account();
        account.setUserId(userId);
        account.setName("Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(10000));
        account = accountRepository.save(account);
        Category category = new Category();
        category.setUserId(userId);
        category.setName("Other " + UUID.randomUUID());
        category = categoryRepository.save(category);
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(account.getId());
        t.setCategoryId(category.getId());
        t.setTxnDate(date);
        t.setAmount(BigDecimal.valueOf(100));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription(description);
        t.setSource(Transaction.Source.MANUAL);
        t.setCounterpartyType(type);
        return transactionRepository.save(t);
    }
}
