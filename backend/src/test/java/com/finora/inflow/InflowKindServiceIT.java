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
    @Autowired private com.finora.repository.SenderInflowRuleRepository rules;

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

    /** Run {@code task} on four threads released together; what each returned or threw, in order. */
    private static List<Object> together(java.util.concurrent.Callable<?> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<?> f : futures) {
                try {
                    outcomes.add(f.get(30, TimeUnit.SECONDS));
                } catch (java.util.concurrent.ExecutionException e) {
                    outcomes.add(e.getCause());
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    // Found by a local break-test: tapping "Every payment from this sender" twice (or four requests
    // at once) stored the one rule, but three of the four answered "That conflicts with a record
    // that already exists -- refresh and try again" for a choice that had in fact been saved.
    @Test
    void theSameSenderChoiceMadeFourTimesAtOnceSucceedsEveryTime() throws Exception {
        UUID userId = newUser();
        UUID account = newAccount(userId);
        String key = "vpa:race-" + UUID.randomUUID();
        Transaction first = credit(userId, account, key);
        credit(userId, account, key);
        UUID family = service.list(userId).stream().filter(k -> "FAMILY_SUPPORT".equals(k.builtIn())).findFirst()
                .orElseThrow().id();

        List<Object> outcomes = together(() -> service.setChoice(userId, first.getId(),
                new InflowDtos.SetChoiceRequest(family, com.finora.service.InflowChoices.Scope.SENDER)));

        assertThat(outcomes).allSatisfy(o -> assertThat(o).isInstanceOf(InflowDtos.CountsAsDto.class));
        assertThat(rules.findByUserId(userId)).singleElement()
                .satisfies(r -> assertThat(r.getInflowKindId()).isEqualTo(family));
    }

    // Saying what a payment was is the review of that payment. The flag used to stay set, so an
    // answered credit kept its "Needs Review" badge -- and Quick sort, which leaves such credits to
    // this question, never cleared it either.
    @Test
    void aRowChoiceClearsThatPaymentsReviewFlag() {
        UUID userId = newUser();
        UUID account = newAccount(userId);
        Transaction row = waiting(credit(userId, account, "vpa:row-" + UUID.randomUUID()));
        Transaction other = waiting(credit(userId, account, "vpa:other-" + UUID.randomUUID()));

        service.setChoice(userId, row.getId(), new InflowDtos.SetChoiceRequest(builtIn(userId, "INCOME"),
                com.finora.service.InflowChoices.Scope.ROW));

        assertThat(transactions.findById(row.getId()).orElseThrow().isNeedsCategoryReview()).isFalse();
        assertThat(transactions.findById(other.getId()).orElseThrow().isNeedsCategoryReview()).isTrue();
    }

    @Test
    void aSenderChoiceClearsTheReviewFlagOnThatSendersCreditsOnly() {
        UUID userId = newUser();
        UUID account = newAccount(userId);
        String key = "vpa:sender-" + UUID.randomUUID();
        Transaction first = waiting(credit(userId, account, key));
        Transaction second = waiting(credit(userId, account, key));
        Transaction paidToThem = credit(userId, account, key);
        paidToThem.setTxnType(Transaction.Type.EXPENSE);
        paidToThem = waiting(paidToThem);
        Transaction someoneElse = waiting(credit(userId, account, "vpa:else-" + UUID.randomUUID()));
        UUID otherUser = newUser();
        Transaction otherUsersRow = waiting(credit(otherUser, newAccount(otherUser), key));

        service.setChoice(userId, first.getId(), new InflowDtos.SetChoiceRequest(builtIn(userId, "FAMILY_SUPPORT"),
                com.finora.service.InflowChoices.Scope.SENDER));

        assertThat(transactions.findById(first.getId()).orElseThrow().isNeedsCategoryReview()).isFalse();
        assertThat(transactions.findById(second.getId()).orElseThrow().isNeedsCategoryReview()).isFalse();
        assertThat(transactions.findById(paidToThem.getId()).orElseThrow().isNeedsCategoryReview()).isTrue();
        assertThat(transactions.findById(someoneElse.getId()).orElseThrow().isNeedsCategoryReview()).isTrue();
        assertThat(transactions.findById(otherUsersRow.getId()).orElseThrow().isNeedsCategoryReview()).isTrue();
    }

    private Transaction waiting(Transaction t) {
        t.setNeedsCategoryReview(true);
        return transactions.save(t);
    }

    private UUID builtIn(UUID userId, String name) {
        return service.list(userId).stream().filter(k -> name.equals(k.builtIn())).findFirst().orElseThrow().id();
    }

    @Test
    void aSecondSenderChoiceReplacesTheFirst() {
        UUID userId = newUser();
        UUID account = newAccount(userId);
        String key = "vpa:switch-" + UUID.randomUUID();
        Transaction row = credit(userId, account, key);
        List<InflowDtos.InflowKindDto> builtIns = service.list(userId);
        UUID family = builtIns.stream().filter(k -> "FAMILY_SUPPORT".equals(k.builtIn())).findFirst().orElseThrow().id();
        UUID own = builtIns.stream().filter(k -> "OWN_MONEY".equals(k.builtIn())).findFirst().orElseThrow().id();

        service.setChoice(userId, row.getId(), new InflowDtos.SetChoiceRequest(family, com.finora.service.InflowChoices.Scope.SENDER));
        InflowDtos.CountsAsDto after = service.setChoice(userId, row.getId(),
                new InflowDtos.SetChoiceRequest(own, com.finora.service.InflowChoices.Scope.SENDER));

        assertThat(after.kind().id()).isEqualTo(own);
        assertThat(rules.findByUserId(userId)).singleElement()
                .satisfies(r -> assertThat(r.getInflowKindId()).isEqualTo(own));
    }

    // The same break-test: four creates of one new name at once stored one kind, and two of the three
    // refusals were a generic "conflicts with a record" instead of "You already have a kind with this name".
    @Test
    void fourCreatesOfOneNameAtOnceStoreOneKindAndRefuseTheRestByName() throws Exception {
        UUID userId = newUser();
        service.list(userId);

        List<Object> outcomes = together(() -> service.create(userId, new InflowDtos.CreateKindRequest("Race kind", true)));

        assertThat(outcomes).filteredOn(o -> o instanceof InflowDtos.InflowKindDto).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !(o instanceof InflowDtos.InflowKindDto))
                .hasSize(3)
                .allSatisfy(o -> assertThat(o).isInstanceOfSatisfying(com.finora.exception.ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo(com.finora.exception.ErrorCode.INFLOW_KIND_NAME_TAKEN)));
        assertThat(kinds.findByUserId(userId)).filteredOn(k -> "race kind".equals(k.getName().toLowerCase())).hasSize(1);
    }
}
