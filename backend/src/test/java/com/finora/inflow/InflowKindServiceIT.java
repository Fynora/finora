package com.finora.inflow;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.InflowKindRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan 2's kind rules that only real Postgres can prove: the built-in race and the soft-deleted FK. */
class InflowKindServiceIT extends AbstractIntegrationTest {

    @Autowired private InflowKindService service;
    @Autowired private InflowKindRepository kinds;
    @Autowired private UserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private TransactionRepository transactions;

    private UUID newUser() {
        User u = new User();
        u.setEmail("inflow-svc-it-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("irrelevant");
        u.setFullName("Inflow Service IT");
        u.setAccountScope(User.SCOPE_USER);
        return users.save(u).getId();
    }

    @Test
    void parallelFirstCallsCreateExactlyFiveBuiltIns() throws Exception {
        UUID userId = newUser();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return service.list(userId);
                }));
            }
            go.countDown();
            for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(kinds.countByUserIdAndBuiltInIsNotNull(userId)).isEqualTo(5);
    }

    @Test
    void deleteClearsSoftDeletedRowsFirst() {
        UUID userId = newUser();
        InflowDtos.InflowKindDto rent = service.create(userId, new InflowDtos.CreateKindRequest("Rent from tenant", true));
        Account account = new Account();
        account.setUserId(userId);
        account.setName("Inflow IT Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(1000));
        UUID accountId = accounts.save(account).getId();
        Transaction txn = new Transaction();
        txn.setUserId(userId);
        txn.setAccountId(accountId);
        txn.setTxnDate(LocalDate.now());
        txn.setAmount(BigDecimal.valueOf(500));
        txn.setTxnType(Transaction.Type.INCOME);
        txn.setDescription("NEFT CR-HDFC0XXXXXX-TENANT ONE-ACCOUNT HOLDER-111111111111");
        txn.setInflowKindId(rent.id());
        txn = transactions.save(txn);
        transactions.delete(txn); // soft delete: the row keeps its inflow_kind_id

        service.delete(userId, rent.id());

        assertThat(kinds.findById(rent.id())).isEmpty();
    }

    private UUID newAccount(UUID userId) {
        Account account = new Account();
        account.setUserId(userId);
        account.setName("Inflow IT Savings " + UUID.randomUUID());
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(1000));
        return accounts.save(account).getId();
    }

    private Transaction credit(UUID userId, UUID accountId, String key) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setTxnDate(LocalDate.now());
        t.setAmount(BigDecimal.valueOf(500));
        t.setTxnType(Transaction.Type.INCOME);
        t.setDescription("UPI-ASHA VERMA-asha@okbank-HDFC0XXXXXX-111111111111-UPI");
        t.setCounterpartyKey(key);
        return transactions.save(t);
    }

    /** "Every payment from X (N)" must count only the payments a sender choice can change. */
    @Test
    void senderCountLeavesOutPaymentsTheChoiceCannotChange() {
        UUID userId = newUser();
        UUID live = newAccount(userId), gone = newAccount(userId);
        String key = "vpa:asha-" + UUID.randomUUID();
        credit(userId, live, key);                                   // counts
        credit(userId, gone, key);                                   // account deleted below
        Transaction paired = credit(userId, live, key);              // a matched transfer leg
        paired.setTransfer(true);
        transactions.save(paired);
        Transaction refundLeg = credit(userId, live, key);           // linked as a refund
        refundLeg.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);
        transactions.save(refundLeg);
        Transaction original = credit(userId, live, key);
        Transaction duplicate = credit(userId, live, key);           // a duplicate of `original`
        duplicate.setIsDuplicateOf(original.getId());
        transactions.save(duplicate);
        Transaction debit = credit(userId, live, key);               // money going out
        debit.setTxnType(Transaction.Type.EXPENSE);
        transactions.save(debit);
        accounts.delete(accounts.findById(gone).orElseThrow());

        assertThat(transactions.countLiveCreditsBySender(userId, key)).isEqualTo(2L); // the first and `original`
    }

    /** A credit later edited into a debit keeps its row choice, which can never apply again and has
     *  no "Counts as" row to clear it from -- it must not keep its kind undeletable. */
    @Test
    void deleteIgnoresARowChoiceLeftOnADebit() {
        UUID userId = newUser();
        InflowDtos.InflowKindDto rent = service.create(userId, new InflowDtos.CreateKindRequest("Rent from tenant", true));
        Transaction t = credit(userId, newAccount(userId), "vpa:tenant-" + UUID.randomUUID());
        t.setInflowKindId(rent.id());
        t.setTxnType(Transaction.Type.EXPENSE);
        transactions.save(t);

        service.delete(userId, rent.id());

        assertThat(kinds.findById(rent.id())).isEmpty();
        assertThat(transactions.findById(t.getId()).orElseThrow().getInflowKindId()).isNull();
    }
}
