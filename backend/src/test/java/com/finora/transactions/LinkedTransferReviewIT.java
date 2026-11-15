package com.finora.transactions;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.ReconciliationService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A payment linked as a transfer between the user's own accounts stops waiting for a category, and
 * unlinking puts back the flag the link took -- through the real link paths and the database (spec
 * docs/superpowers/specs/2026-11-15-card-bill-issuer-list-design.md, fix 2).
 */
class LinkedTransferReviewIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private TransactionService transactionService;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;

    private User user;
    private UUID savingsA;
    private UUID savingsB;
    private UUID otherId;

    @BeforeEach
    void setUp() {
        User u = new User();
        u.setEmail("linked-transfer-it-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("irrelevant-for-this-test");
        u.setFullName("Linked Transfer Test User");
        u.setAccountScope(User.SCOPE_USER);
        u.setPhoneVerified(true);
        user = userRepository.save(u);
        savingsA = account("Test Savings A");
        savingsB = account("Test Savings B");
        Category other = new Category();
        other.setUserId(user.getId());
        other.setName("Other");
        otherId = categoryRepository.save(other).getId();
    }

    @Test
    void reconciliationPairingTwoWaitingRowsStopsBothWaiting_andUnmarkPutsThemBack() throws Exception {
        UUID out = waiting(savingsA, Transaction.Type.EXPENSE, "NEFT TO SELF TRANSFER 100000000001");
        UUID in = waiting(savingsB, Transaction.Type.INCOME, "NEFT FROM SELF TRANSFER 100000000001");

        reconciliationService.reconcileForUser(user.getId());

        assertThat(reload(out).isTransfer()).as("reconciliation paired the rows").isTrue();
        for (UUID id : new UUID[] {out, in}) {
            assertThat(reload(id).isNeedsCategoryReview()).isFalse();
            assertThat(reload(id).isReviewClearedByTransfer()).isTrue();
        }

        ResponseEntity<String> undo = post("/api/v1/transactions/" + out + "/unmark-transfer");
        assertThat(undo.getStatusCode()).as(undo.getBody()).isEqualTo(HttpStatus.OK);

        for (UUID id : new UUID[] {out, in}) {
            assertThat(reload(id).isTransfer()).isFalse();
            assertThat(reload(id).isNeedsCategoryReview()).isTrue();
            assertThat(reload(id).isReviewClearedByTransfer()).isFalse();
        }
    }

    @Test
    void aCategoryChosenWhileLinkedSurvivesTheUnmark() throws Exception {
        UUID out = waiting(savingsA, Transaction.Type.EXPENSE, "SAMPLE PAYMENT 100000000002");
        UUID in = waiting(savingsB, Transaction.Type.INCOME, "SAMPLE RECEIPT 100000000002");

        ResponseEntity<String> mark = post("/api/v1/transactions/" + out + "/mark-transfer",
                "{\"pairedTransactionId\":\"" + in + "\"}");
        assertThat(mark.getStatusCode()).as(mark.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(reload(out).isNeedsCategoryReview()).isFalse();
        assertThat(reload(in).isNeedsCategoryReview()).isFalse();

        transactionService.updateCategory(user.getId(), out, "Other", TransactionDto.CategoryScope.ONLY_THIS);
        post("/api/v1/transactions/" + out + "/unmark-transfer");

        assertThat(reload(out).isNeedsCategoryReview()).as("chosen by hand while linked").isFalse();
        assertThat(reload(in).isNeedsCategoryReview()).as("untouched partner waits again").isTrue();
    }

    private UUID account(String name) {
        Account a = new Account();
        a.setUserId(user.getId());
        a.setName(name);
        a.setAccountType(Account.Type.SAVINGS);
        a.setBalance(BigDecimal.valueOf(10000));
        return accountRepository.save(a).getId();
    }

    private UUID waiting(UUID account, Transaction.Type type, String description) {
        Transaction t = new Transaction();
        t.setUserId(user.getId());
        t.setAccountId(account);
        t.setCategoryId(otherId);
        t.setTxnDate(LocalDate.of(2026, 10, 1));
        t.setAmount(BigDecimal.valueOf(5000));
        t.setTxnType(type);
        t.setDescription(description);
        t.setDecisionSource(Transaction.DecisionSource.MERCHANT_DEFAULT);
        t.setNeedsCategoryReview(true);
        return transactionRepository.save(t).getId();
    }

    private Transaction reload(UUID id) {
        return transactionRepository.findById(id).orElseThrow();
    }

    private ResponseEntity<String> post(String path) {
        return post(path, null);
    }

    private ResponseEntity<String> post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
