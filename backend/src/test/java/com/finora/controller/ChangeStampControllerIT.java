package com.finora.controller;

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
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mobile app polls this to learn that something changed on another device. What matters is
 * therefore not the values but WHEN each one moves, and WHICH: every kind of change a user can make
 * elsewhere must move the section that holds it, and only that one -- the app refetches only what a
 * moved section names, and it uses an unmoved section to know its own edit changed nothing else.
 */
class ChangeStampControllerIT extends AbstractIntegrationTest {

    private static final Set<String> SECTIONS = Set.of(
            "transactions", "accounts", "statementImports", "budgets", "goals", "categories", "profile");

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private PlatformTransactionManager transactionManager;

    private User user;
    private Account account;
    private Category category;

    @BeforeEach
    void setUp() {
        user = newUser("change-stamp-it");
        account = newAccount(user, "Stamp Savings");
        category = new Category();
        category.setUserId(user.getId());
        category.setName("Stamp Dining");
        category = categoryRepository.save(category);
    }

    private User newUser(String prefix) {
        User u = new User();
        u.setEmail(prefix + "-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("not-used");
        u.setFullName("Google Name");
        // PhoneVerificationFilter gates the whole authenticated API on this.
        u.setPhoneVerified(true);
        return userRepository.save(u);
    }

    private Account newAccount(User owner, String name) {
        Account a = new Account();
        a.setUserId(owner.getId());
        a.setName(name);
        a.setAccountType(Account.Type.SAVINGS);
        a.setBalance(BigDecimal.valueOf(1000));
        return accountRepository.save(a);
    }

    private Transaction newTransaction(String description) {
        Transaction t = new Transaction();
        t.setUserId(user.getId());
        t.setAccountId(account.getId());
        t.setCategoryId(category.getId());
        t.setTxnDate(LocalDate.of(2026, 7, 10));
        t.setAmount(BigDecimal.valueOf(500));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription(description);
        t.setSource(Transaction.Source.MANUAL);
        return transactionRepository.save(t);
    }

    private Map<String, String> stampOf(User who) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, who));
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/changes/stamp", HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, String> data = (Map<String, String>) response.getBody().get("data");
        assertThat(data.keySet()).containsExactlyInAnyOrderElementsOf(SECTIONS);
        data.values().forEach(v -> assertThat(v).isNotBlank());
        return data;
    }

    /** Exactly these sections differ between the two readings; every other one is unchanged. */
    private static void assertMovedOnly(Map<String, String> before, Map<String, String> after, String... expected) {
        Set<String> moved = new TreeSet<>();
        SECTIONS.forEach(s -> {
            if (!before.get(s).equals(after.get(s))) moved.add(s);
        });
        assertThat(moved).containsExactlyInAnyOrder(expected);
    }

    @Test
    void requiresASession() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/changes/stamp", String.class);
        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    void staysTheSameWhileNothingChanges() {
        assertMovedOnly(stampOf(user), stampOf(user));
    }

    @Test
    void movesOnlyTheProfileWhenTheAccountIsRenamedElsewhere() {
        Map<String, String> before = stampOf(user);

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        reloaded.setFullName("Fynora");
        userRepository.save(reloaded);

        assertMovedOnly(before, stampOf(user), "profile");
    }

    @Test
    void movesOnlyAccountsWhenAnAccountIsAdded() {
        Map<String, String> before = stampOf(user);
        newAccount(user, "Second account");
        assertMovedOnly(before, stampOf(user), "accounts");
    }

    @Test
    void movesOnlyAccountsWhenAnAccountIsEditedWithoutTheCountChanging() {
        Map<String, String> before = stampOf(user);

        Account reloaded = accountRepository.findById(account.getId()).orElseThrow();
        reloaded.setName("Renamed on the web");
        accountRepository.save(reloaded);

        assertMovedOnly(before, stampOf(user), "accounts");
    }

    @Test
    void movesOnlyTransactionsWhenTransactionsAreImported() {
        Map<String, String> before = stampOf(user);
        newTransaction("Imported from a statement");
        assertMovedOnly(before, stampOf(user), "transactions");
    }

    @Test
    void movesOnlyTransactionsWhenATransactionIsDeleted() {
        Transaction t = newTransaction("To be deleted");
        Map<String, String> before = stampOf(user);

        transactionRepository.delete(t);

        assertMovedOnly(before, stampOf(user), "transactions");
    }

    @Test
    void movesWhenOneTransactionIsDeletedAndAnotherAddedAtOnce() {
        // Count and version-sum are both unchanged by this pair; only the newest created_at differs.
        Transaction t = newTransaction("Goes away");
        Map<String, String> before = stampOf(user);

        transactionRepository.delete(t);
        newTransaction("Arrives");

        assertMovedOnly(before, stampOf(user), "transactions");
    }

    @Test
    void movesOnlyTransactionsWhenDeletingACategoryReassignsThemInBulk() {
        // CategoryService.delete moves every affected transaction with one bulk UPDATE, which
        // bypasses Hibernate's lifecycle: without an explicit version bump, no row is added or
        // removed and no version moves, so the phone would never learn the categories changed.
        newTransaction("Filed under the category being deleted");
        Category replacement = new Category();
        replacement.setUserId(user.getId());
        replacement.setName("Stamp Replacement");
        replacement = categoryRepository.save(replacement);
        Map<String, String> before = stampOf(user);

        UUID replacementId = replacement.getId();
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> transactionRepository.reassignCategory(user.getId(), category.getId(), replacementId));

        assertMovedOnly(before, stampOf(user), "transactions");
    }

    @Test
    void movesOnlyTheProfileWhenContactDetailsChangeElsewhere() {
        Map<String, String> before = stampOf(user);

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        reloaded.setPhoneNumber("+9198" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000));
        userRepository.save(reloaded);

        assertMovedOnly(before, stampOf(user), "profile");
    }

    @Test
    void movesOnlyCategoriesWhenACategoryIsRenamedElsewhere() {
        // categories have no version or timestamp column, so this is the case a count cannot see.
        Map<String, String> before = stampOf(user);

        Category reloaded = categoryRepository.findById(category.getId()).orElseThrow();
        reloaded.setName("Renamed on the web");
        categoryRepository.save(reloaded);

        assertMovedOnly(before, stampOf(user), "categories");
    }

    @Test
    void movesOnlyCategoriesWhenACategoryIsAdded() {
        Map<String, String> before = stampOf(user);

        Category added = new Category();
        added.setUserId(user.getId());
        added.setName("Added on the web");
        categoryRepository.save(added);

        assertMovedOnly(before, stampOf(user), "categories");
    }

    @Test
    void movesOnlyTheProfileWhenItsOtherDisplayedFieldsChange() {
        Map<String, String> before = stampOf(user);

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        reloaded.setPasswordChangedAt(Instant.now());
        userRepository.save(reloaded);
        Map<String, String> afterPassword = stampOf(user);
        assertMovedOnly(before, afterPassword, "profile");

        reloaded = userRepository.findById(user.getId()).orElseThrow();
        reloaded.setTimezone("America/New_York");
        userRepository.save(reloaded);
        assertMovedOnly(afterPassword, stampOf(user), "profile");
    }

    @Test
    void ignoresAnotherUsersActivity() {
        User other = newUser("change-stamp-other");
        Map<String, String> before = stampOf(user);

        newAccount(other, "Someone else's account");
        User reloaded = userRepository.findById(other.getId()).orElseThrow();
        reloaded.setFullName("Someone Else Renamed");
        userRepository.save(reloaded);

        assertMovedOnly(before, stampOf(user));
    }
}
