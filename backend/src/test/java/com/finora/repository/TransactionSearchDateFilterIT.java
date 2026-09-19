package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production, 2026-09-19: {@code GET /api/v1/transactions} returned 500 for every request that
 * carried a date filter -- PostgreSQL "could not determine data type of parameter $N", raised by
 * the {@code :dateFrom IS NULL} / {@code :dateTo IS NULL} guards when the date is NON-null (a
 * LocalDate bound as an untyped parameter cannot be typed from a bare {@code ? IS NULL}).
 *
 * <p>Deliberately not {@code @Transactional}: a failed statement aborts the surrounding
 * transaction, and every later query in the same test would then fail with an unrelated message.
 * Each case builds its own user, so nothing is shared.
 */
class TransactionSearchDateFilterIT extends AbstractIntegrationTest {

    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;

    private record Fixture(UUID userId, List<UUID> accountIds) {}

    private Fixture fixture(int accounts) {
        User user = new User();
        user.setEmail("txn-date-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Txn Date IT");
        user = userRepository.save(user);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < accounts; i++) {
            Account a = new Account();
            a.setUserId(user.getId());
            a.setName("Account " + i);
            a.setAccountType(Account.Type.SAVINGS);
            a.setBalance(BigDecimal.TEN);
            ids.add(accountRepository.save(a).getId());
        }
        UUID accountId = ids.get(0);
        for (LocalDate d : List.of(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 7, 10), LocalDate.of(2026, 8, 5))) {
            Transaction t = new Transaction();
            t.setUserId(user.getId());
            t.setAccountId(accountId);
            t.setAmount(BigDecimal.valueOf(100));
            t.setTxnType(Transaction.Type.EXPENSE);
            t.setTxnDate(d);
            t.setDescription("Row on " + d);
            transactionRepository.save(t);
        }
        return new Fixture(user.getId(), ids);
    }

    private List<LocalDate> datesFor(Fixture f, LocalDate from, LocalDate to) {
        return transactionRepository.search(f.userId(), null, null, null, null, from, to, null, null, null,
                        List.of("NONE"), List.of(UUID.randomUUID()), f.accountIds(), PageRequest.of(0, 20))
                .getContent().stream().map(Transaction::getTxnDate).sorted().toList();
    }

    @Test
    void aFromDateAloneFiltersRatherThanFailing() {
        assertThat(datesFor(fixture(2), LocalDate.of(2026, 7, 1), null))
                .containsExactly(LocalDate.of(2026, 7, 10), LocalDate.of(2026, 8, 5));
    }

    @Test
    void aToDateAloneFiltersRatherThanFailing() {
        assertThat(datesFor(fixture(2), null, LocalDate.of(2026, 7, 31)))
                .containsExactly(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 7, 10));
    }

    @Test
    void bothDatesFilterTheRangeInclusively() {
        assertThat(datesFor(fixture(1), LocalDate.of(2026, 7, 10), LocalDate.of(2026, 8, 5)))
                .containsExactly(LocalDate.of(2026, 7, 10), LocalDate.of(2026, 8, 5));
    }

    /** The parameter index of the failing placeholder moves with the number of live accounts. */
    @Test
    void theFilterWorksForAnyNumberOfLiveAccounts() {
        for (int accounts : new int[]{1, 2, 3, 5}) {
            assertThat(datesFor(fixture(accounts), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)))
                    .as("%d live accounts", accounts)
                    .containsExactly(LocalDate.of(2026, 7, 10));
        }
    }

    @Test
    void noDateFilterStillReturnsEverything() {
        assertThat(datesFor(fixture(2), null, null)).hasSize(3);
    }
}
