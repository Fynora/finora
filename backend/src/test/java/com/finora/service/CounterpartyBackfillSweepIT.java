package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.util.CounterpartyClassifier;
import com.finora.util.CounterpartyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backfill against a real Postgres. This is the only test that can prove the things that
 * actually break a backfill, and none of them are provable with a mocked repository:
 *
 * <ul>
 *   <li>the JPQL in {@code findRowsNeedingCounterpartyTyping} and {@code applyCounterpartyTyping}
 *       parses and binds at all -- a mock accepts a query that would never run;</li>
 *   <li>a row seeded the way a pre-V143 row exists (no classifier version) is actually discovered;</li>
 *   <li>the bulk update writes the enum, the key and the version to the columns it names;</li>
 *   <li>a stamped row leaves the candidate set, so the sweep terminates instead of spinning.</li>
 * </ul>
 *
 * <h2>Written to survive other tests' rows</h2>
 *
 * <p>The discovery query is table-wide and {@code LIMIT}ed, with no user scope -- correct in
 * production, hostile here, and precisely the failure {@code AbstractIntegrationTest}'s own
 * {@code emptyTheSharedWorkQueues} documents for the two work queues. Transactions cannot be
 * emptied the same way (other classes' rows are their fixtures, not leftovers), so this test never
 * assumes its rows are the only candidates, never asserts on a count, and drains to completion
 * rather than sweeping once. Every assertion is on an id it seeded itself.
 */
class CounterpartyBackfillSweepIT extends AbstractIntegrationTest {

    @Autowired private CounterpartyBackfillSweepService sweepService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private com.finora.repository.InflowKindRepository inflowKindRepository;
    @Autowired private com.finora.repository.SenderInflowRuleRepository senderInflowRuleRepository;
    @Autowired private com.finora.repository.UserMerchantCategoryResolutionRepository userMerchantCategoryResolutionRepository;
    @Autowired private com.finora.repository.CategoryRepository categoryRepository;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("counterparty-backfill-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Test User");
        userId = userRepository.save(user).getId();

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(10000));
        accountId = accountRepository.save(account).getId();

        // Big enough that a drain finishes in few passes even behind another class's rows.
        ReflectionTestUtils.setField(sweepService, "batchSize", 500);
    }

    @Test
    void itTypesRowsThatPredateTheColumn_acrossEveryAnswerTheClassifierGives() {
        Map<String, UUID> seeded = new LinkedHashMap<>();
        seeded.put("UPI-SUNIL VERMA-sampleuser@ybl-REF81", seedUntyped("UPI-SUNIL VERMA-sampleuser@ybl-REF81"));
        seeded.put("UPI-PAYTMQR281005-mer@paytm-REF82", seedUntyped("UPI-PAYTMQR281005-mer@paytm-REF82"));
        seeded.put("NEFT-ACME MANUFACTURING PVT LTD-REF83", seedUntyped("NEFT-ACME MANUFACTURING PVT LTD-REF83"));
        seeded.put("UPI/REF84/UPI", seedUntyped("UPI/REF84/UPI"));

        // Seeded rows really are in the pre-backfill state -- otherwise this test would pass
        // without the sweep doing anything.
        seeded.values().forEach(id ->
                assertThat(reload(id).getCounterpartyClassifierVersion()).isNull());

        drain();

        // Each row gets the answer the classifier itself gives for its narration. Asserting against
        // the classifier rather than against hardcoded types keeps this a test of the BACKFILL --
        // the classifier's own answers are pinned by CounterpartyClassifierTest, and duplicating
        // them here would just make a vocabulary change fail in two places.
        seeded.forEach((narration, id) -> {
            Transaction typed = reload(id);
            assertThat(typed.getCounterpartyType())
                    .as("type for %s", narration)
                    .isEqualTo(CounterpartyClassifier.classify(narration));
            assertThat(typed.getCounterpartyClassifierVersion())
                    .as("version for %s", narration)
                    .isEqualTo(CounterpartyClassifier.VERSION);
        });

        // And the answers are not all the same value -- a backfill that wrote UNKNOWN everywhere
        // would satisfy every assertion above.
        assertThat(reload(seeded.get("UPI-SUNIL VERMA-sampleuser@ybl-REF81")).getCounterpartyType())
                .isEqualTo(CounterpartyType.PERSON);
        assertThat(reload(seeded.get("UPI-PAYTMQR281005-mer@paytm-REF82")).getCounterpartyType())
                .isEqualTo(CounterpartyType.BUSINESS);
        assertThat(reload(seeded.get("UPI/REF84/UPI")).getCounterpartyType())
                .isEqualTo(CounterpartyType.UNKNOWN);
    }

    @Test
    void theKeyIsWrittenAndAnUnderivableOneIsStoredAsNull() {
        UUID withKey = seedUntyped("UPI-SUNIL VERMA-sampleuser@ybl-REF85");
        UUID withoutKey = seedUntyped("UPI/REF86/UPI");

        drain();

        assertThat(reload(withKey).getCounterpartyKey()).isEqualTo("vpa:sampleuser");
        // NULL, not "" -- two "no key" representations would become two groups in the GROUP BY the
        // value-weighted review runs, and Postgres is where that distinction is real.
        assertThat(reload(withoutKey).getCounterpartyKey()).isNull();
    }

    /**
     * Plan 2: a user's remembered sender is keyed on counterparty_key. When a classifier bump makes
     * the sweep re-key a row, the rule must follow the new key -- otherwise every payment from
     * that sender silently drops back to "not counted" with nothing telling the user why.
     */
    @Test
    void aRememberedSenderFollowsItsRowsToTheirNewKey() {
        UUID kindId = newKind("Family support");
        UUID otherKindId = newKind("Rent from tenant");
        UUID moved = seedTypedWithOldKey("UPI-SUNIL VERMA-sampleuser@ybl-REF90", "name:sunil verma");
        remember("name:sunil verma", kindId);
        // A second sender whose new key already has its own rule: that newer answer is kept.
        UUID kept = seedTypedWithOldKey("UPI-ASHA VERMA-ashauser@ybl-REF91", "name:asha verma");
        remember("name:asha verma", kindId);
        remember("vpa:ashauser", otherKindId);

        drain();

        assertThat(reload(moved).getCounterpartyKey()).isEqualTo("vpa:sampleuser");
        assertThat(senderInflowRuleRepository.findByUserIdAndCounterpartyKey(userId, "vpa:sampleuser"))
                .map(com.finora.entity.SenderInflowRule::getInflowKindId).contains(kindId);
        assertThat(reload(kept).getCounterpartyKey()).isEqualTo("vpa:ashauser");
        assertThat(senderInflowRuleRepository.findByUserIdAndCounterpartyKey(userId, "vpa:ashauser"))
                .map(com.finora.entity.SenderInflowRule::getInflowKindId).contains(otherKindId);
    }

    /**
     * The same move for a learned category. A user's correction ("this sender is Groceries") and the
     * AI resolution cache are both keyed on counterparty_key: left on the old key, the sender's next
     * import would lose the category the user taught.
     */
    @Test
    void aLearnedCategoryFollowsItsRowsToTheirNewKey() {
        UUID groceries = newCategory("Groceries");
        UUID dining = newCategory("Dining");
        UUID moved = seedTypedWithOldKey("UPI-SUNIL VERMA-sampleuser@ybl-REF92", "name:sunil verma");
        userMerchantCategoryResolutionRepository.upsertPinned(userId, "name:sunil verma", "INCOME", groceries, java.time.Instant.now());
        // The new key already has its own answer: that one is kept.
        UUID kept = seedTypedWithOldKey("UPI-ASHA VERMA-ashauser@ybl-REF93", "name:asha verma");
        userMerchantCategoryResolutionRepository.upsertPinned(userId, "name:asha verma", "INCOME", groceries, java.time.Instant.now());
        userMerchantCategoryResolutionRepository.upsertPinned(userId, "vpa:ashauser", "INCOME", dining, java.time.Instant.now());

        drain();

        assertThat(reload(moved).getCounterpartyKey()).isEqualTo("vpa:sampleuser");
        assertThat(userMerchantCategoryResolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(
                userId, "vpa:sampleuser", Transaction.Type.INCOME))
                .map(com.finora.entity.UserMerchantCategoryResolution::getCategoryId).contains(groceries);
        assertThat(reload(kept).getCounterpartyKey()).isEqualTo("vpa:ashauser");
        assertThat(userMerchantCategoryResolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(
                userId, "vpa:ashauser", Transaction.Type.INCOME))
                .map(com.finora.entity.UserMerchantCategoryResolution::getCategoryId).contains(dining);
    }

    private UUID newCategory(String name) {
        com.finora.entity.Category c = new com.finora.entity.Category();
        c.setUserId(userId);
        c.setName(name);
        return categoryRepository.save(c).getId();
    }

    private UUID newKind(String name) {
        com.finora.entity.InflowKind k = new com.finora.entity.InflowKind();
        k.setUserId(userId);
        k.setName(name);
        k.setCountsAsIncome(true);
        return inflowKindRepository.save(k).getId();
    }

    private void remember(String key, UUID kindId) {
        com.finora.entity.SenderInflowRule r = new com.finora.entity.SenderInflowRule();
        r.setUserId(userId);
        r.setCounterpartyKey(key);
        r.setInflowKindId(kindId);
        senderInflowRuleRepository.save(r);
    }

    /** A credit typed by an older classifier revision, under the key that revision derived. */
    private UUID seedTypedWithOldKey(String description, String oldKey) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setTxnDate(LocalDate.now());
        t.setAmount(BigDecimal.valueOf(486));
        t.setTxnType(Transaction.Type.INCOME);
        t.setDescription(description);
        t.setCounterpartyKey(oldKey);
        t.setCounterpartyClassifierVersion((short) 1);
        return transactionRepository.save(t).getId();
    }

    @Test
    void aTypedRowIsNotPickedUpAgain_soTheSweepTerminates() {
        UUID id = seedUntyped("UPI-SUNIL VERMA-sampleuser@ybl-REF87");
        drain();

        // The stamp is what removes a row from the candidate set. Without this the sweep would
        // re-type the whole table on every pass, forever, and still look like it was working.
        var afterDrain = sweepService.sweep();

        assertThat(afterDrain.drained()).isTrue();
        assertThat(afterDrain.typed()).isZero();
        assertThat(reload(id).getCounterpartyClassifierVersion()).isEqualTo(CounterpartyClassifier.VERSION);
    }

    @Test
    void aRowWithNoNarrationIsTypedUnknownAndStamped_ratherThanRediscoveredForever() {
        // transactions.description is nullable (V1), so these exist. An unstamped one would be
        // rediscovered on every pass for the life of the table.
        UUID id = seedUntyped(null);

        drain();

        Transaction typed = reload(id);
        assertThat(typed.getCounterpartyType()).isEqualTo(CounterpartyType.UNKNOWN);
        assertThat(typed.getCounterpartyKey()).isNull();
        assertThat(typed.getCounterpartyClassifierVersion()).isEqualTo(CounterpartyClassifier.VERSION);
    }

    @Test
    void aLongNarrationIsTypedRatherThanFailingItsBatch() {
        // The regression guard for the VARCHAR(120) overflow: uncapped, keyOf returned 505
        // characters for a 500-character narration, and this UPDATE would have thrown inside the
        // batch transaction -- taking every other row in the batch down with it.
        String longNarration = ("ALPHA ".repeat(84)).substring(0, 500);
        UUID longRow = seedUntyped(longNarration);
        UUID neighbour = seedUntyped("UPI-SUNIL VERMA-sampleuser@ybl-REF88");

        drain();

        assertThat(reload(longRow).getCounterpartyClassifierVersion()).isEqualTo(CounterpartyClassifier.VERSION);
        assertThat(reload(longRow).getCounterpartyKey()).hasSizeLessThanOrEqualTo(120);
        assertThat(reload(neighbour).getCounterpartyClassifierVersion()).isEqualTo(CounterpartyClassifier.VERSION);
    }

    // --- helpers -----------------------------------------------------------------------------

    /**
     * Runs passes until the candidate set is empty. Bounded so a bug that stops the sweep making
     * progress fails the test rather than hanging the suite -- which is the honest way for
     * "the sweep spins forever" to present.
     */
    private void drain() {
        for (int pass = 0; pass < 20; pass++) {
            if (sweepService.sweep().drained()) return;
        }
        throw new AssertionError("Backfill did not drain in 20 passes -- the sweep is not making progress.");
    }

    /** A row in the state every transaction was in before V143: typed by nothing. */
    private UUID seedUntyped(String description) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setTxnDate(LocalDate.now());
        t.setAmount(BigDecimal.valueOf(486));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription(description);
        // Deliberately NOT applyCounterpartyTyping -- that is the live write path, and this is
        // simulating a row that predates it.
        return transactionRepository.save(t).getId();
    }

    private Transaction reload(UUID id) {
        // The sweep writes with a bulk JPQL update, which does not refresh anything already in a
        // persistence context. Nothing is cached across these calls, but reloading explicitly keeps
        // that from being an assumption.
        return transactionRepository.findById(id).orElseThrow();
    }
}
