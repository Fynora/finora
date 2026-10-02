package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.ImportSession;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.imports.ImportService;
import com.finora.imports.ImportSessionService;
import com.finora.imports.RowKind;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserMerchantCategoryResolutionRepository;
import com.finora.repository.UserRepository;
import com.finora.transactions.TransactionDto;
import com.finora.transactions.TransactionService;
import com.finora.util.CounterpartyIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A category chosen for a row whose payee key does not name one payee -- a masked UPI id, or a
 * gateway-only name such as "Pay via Razorpay" -- is not remembered for that key, on every path that
 * remembers one, and a row remembered before that rule existed is not served at import. Each path
 * also gets a row with a full UPI id, which must still be remembered: without it, "nothing was
 * stored" would pass just as well for a path that never stores anything.
 *
 * <p>Through the real services and Postgres rather than unit tests of each caller: those mock the
 * resolution service, so they could only re-assert that the caller passes the key along. The rule
 * itself lives in {@link UserMerchantCategoryResolutionService}, which every path reaches.
 */
class WeakPayeeKeyNotRememberedIT extends AbstractIntegrationTest {

    /** A gateway-only name: the shop was never printed. */
    private static final String GATEWAY = "UPI/RRN 111111111111/Pay via Razorpay";
    /** A masked UPI id: only the tail was printed, and strangers share it. */
    private static final String MASKED = "UPI/DR/222222222222/SAMPLE N/CNRB/**TAILX@OKICICI/UPI";
    /** A full UPI id: names one payee. */
    private static final String FULL = "UPI/DR/333333333333/SAMPLESHOP/HDFC/sampleshopweakkeyit@okhdfc/UPI";

    @Autowired private TransactionService transactionService;
    @Autowired private ImportService importService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private CategorizationService categorizationService;
    @Autowired private UserMerchantCategoryResolutionRepository resolutionRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;

    private final List<UUID> createdUserIds = new ArrayList<>();

    private record Owner(UUID userId, UUID accountId) {}

    private Owner owner() {
        User user = new User();
        user.setEmail("weak-key-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Weak Key IT User");
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        createdUserIds.add(user.getId());
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("10000.00"));
        return new Owner(user.getId(), accountRepository.save(account).getId());
    }

    @AfterEach
    void removeQueuedLearningEvents() {
        if (createdUserIds.isEmpty()) return;
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> createdUserIds.contains(e.getUserId()))
                .toList());
        createdUserIds.clear();
    }

    private static String key(String description) {
        return CounterpartyIdentity.keyOf(description);
    }

    private boolean remembered(Owner o, String description) {
        return resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(
                o.userId(), key(description), Transaction.Type.EXPENSE).isPresent();
    }

    private TransactionDto create(Owner o, String description, String categoryName) {
        return transactionService.create(o.userId(), new TransactionDto.CreateRequest(o.accountId(), categoryName,
                LocalDate.of(2026, 7, 1), description, new BigDecimal("250.00"), "EXPENSE", null, null));
    }

    /** The narrations above must produce the keys this test is about, or it proves nothing. */
    @Test
    void theNarrationsProduceTheKeysUnderTest() {
        assertThat(key(GATEWAY)).startsWith("name:");
        assertThat(CounterpartyIdentity.identifiesOnePayee(key(GATEWAY))).isFalse();
        assertThat(key(MASKED)).startsWith("masked:");
        assertThat(CounterpartyIdentity.identifiesOnePayee(key(MASKED))).isFalse();
        assertThat(key(FULL)).startsWith("vpa:");
        assertThat(CounterpartyIdentity.identifiesOnePayee(key(FULL))).isTrue();
    }

    @Test
    void create_withAChosenCategory() {
        Owner o = owner();
        for (String description : List.of(GATEWAY, MASKED, FULL)) create(o, description, "Dining");

        assertThat(remembered(o, GATEWAY)).isFalse();
        assertThat(remembered(o, MASKED)).isFalse();
        assertThat(remembered(o, FULL)).isTrue();
    }

    @Test
    void updateCategory_withoutAScope() {
        Owner o = owner();
        for (String description : List.of(GATEWAY, MASKED, FULL)) {
            transactionService.updateCategory(o.userId(), create(o, description, null).id(), "Dining");
        }

        assertThat(remembered(o, GATEWAY)).isFalse();
        assertThat(remembered(o, MASKED)).isFalse();
        assertThat(remembered(o, FULL)).isTrue();
    }

    @Test
    void confirmMerchantCategory() {
        Owner o = owner();
        Category dining = categorizationService.resolveOrCreateCategory(o.userId(), "Dining");
        for (String description : List.of(GATEWAY, MASKED, FULL)) {
            Transaction t = transactionRepository.findById(create(o, description, null).id()).orElseThrow();
            assertThat(t.getMerchantId()).as("merchant resolved for %s", description).isNotNull();
            transactionService.confirmMerchantCategory(o.userId(), t.getMerchantId(), t.getId(), dining.getId(), UUID.randomUUID());
        }

        assertThat(remembered(o, GATEWAY)).isFalse();
        assertThat(remembered(o, MASKED)).isFalse();
        assertThat(remembered(o, FULL)).isTrue();
    }

    private static StagedRow staged(String description, int rowPosition) {
        return new StagedRow(LocalDate.of(2026, 7, 1), description, new BigDecimal("250.00"), "EXPENSE",
                "Other", "rule", null, false, null, null, null, RowKind.TRANSACTION, null, null, null, 90)
                .withRowPosition(rowPosition);
    }

    private static ConfirmedRow confirmed(StagedRow r, String category) {
        return new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(), category, true,
                r.categorySource(), r.ruleId(), false, null, null, false, r.categoryConfidence(), r.rowPosition());
    }

    /** Ask-once at import: a category the user picks on the review screen is a decision worth learning. */
    @Test
    void importConfirm_withAChosenCategory() {
        Owner o = owner();
        List<StagedRow> rows = List.of(staged(GATEWAY, 0), staged(MASKED, 1), staged(FULL, 2));
        ImportSession session = importSessionService.createSession(o.userId(), "statement.csv",
                "Date,Description,Amount,Type\n".getBytes(StandardCharsets.UTF_8), rows, null);

        importService.confirmSession(o.userId(), new ConfirmRequest(session.getId(),
                rows.stream().map(r -> confirmed(r, "Dining")).toList(),
                o.accountId(), null, null, null, null));

        assertThat(remembered(o, GATEWAY)).isFalse();
        assertThat(remembered(o, MASKED)).isFalse();
        assertThat(remembered(o, FULL)).isTrue();
    }

    /**
     * Rows remembered before this rule existed, written straight into the table as a database that
     * predates it holds them: the import preview must not serve them for a weak key, and must still
     * serve the full UPI id's.
     */
    @Test
    void importStaging_ignoresARowRememberedBeforeTheRule() throws Exception {
        Owner o = owner();
        Category probe = categorizationService.resolveOrCreateCategory(o.userId(), "Weak Key IT Probe");
        for (String description : List.of(GATEWAY, MASKED, FULL)) {
            resolutionRepository.upsertPinned(o.userId(), key(description), "EXPENSE", probe.getId(), Instant.now());
        }
        byte[] csv = ("Date,Description,Amount,Type\n"
                + "2026-07-01," + GATEWAY + ",250.00,DEBIT\n"
                + "2026-07-02," + MASKED + ",250.00,DEBIT\n"
                + "2026-07-03," + FULL + ",250.00,DEBIT\n").getBytes(StandardCharsets.UTF_8);

        List<StagedRow> staged = importService.parseAndStageAnyFormat(o.userId(), "CSV", "statement.csv", csv, null).rows();

        assertThat(staged).hasSize(3);
        for (StagedRow r : staged) {
            boolean servedTheRememberedRow = probe.getName().equals(r.suggestedCategory());
            if (r.description().equals(FULL)) {
                assertThat(servedTheRememberedRow).as("full UPI id still served: %s", r).isTrue();
                assertThat(r.categorySource()).isEqualTo(CategorizationService.AI_FALLBACK_SOURCE);
            } else {
                assertThat(servedTheRememberedRow).as("weak key not served: %s", r).isFalse();
            }
        }
    }
}
