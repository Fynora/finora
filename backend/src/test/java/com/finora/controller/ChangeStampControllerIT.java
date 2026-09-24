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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mobile app polls this to learn that something changed on another device. What matters is
 * therefore not the value but WHEN it moves: every kind of change a user can make elsewhere must
 * move it, and a change that is not theirs, or no change at all, must not.
 */
class ChangeStampControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;

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

    private String stampOf(User who) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, who));
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/v1/changes/stamp", HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, String> data = (Map<String, String>) response.getBody().get("data");
        assertThat(data.get("stamp")).isNotBlank();
        return data.get("stamp");
    }

    @Test
    void requiresASession() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/changes/stamp", String.class);
        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    void staysTheSameWhileNothingChanges() {
        assertThat(stampOf(user)).isEqualTo(stampOf(user));
    }

    @Test
    void movesWhenTheAccountIsRenamedElsewhere() {
        String before = stampOf(user);

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        reloaded.setFullName("Fynora");
        userRepository.save(reloaded);

        assertThat(stampOf(user)).isNotEqualTo(before);
    }

    @Test
    void movesWhenAnAccountIsAdded() {
        String before = stampOf(user);
        newAccount(user, "Second account");
        assertThat(stampOf(user)).isNotEqualTo(before);
    }

    @Test
    void movesWhenAnAccountIsEditedWithoutTheCountChanging() {
        String before = stampOf(user);

        Account reloaded = accountRepository.findById(account.getId()).orElseThrow();
        reloaded.setName("Renamed on the web");
        accountRepository.save(reloaded);

        assertThat(stampOf(user)).isNotEqualTo(before);
    }

    @Test
    void movesWhenTransactionsAreImported() {
        String before = stampOf(user);
        newTransaction("Imported from a statement");
        assertThat(stampOf(user)).isNotEqualTo(before);
    }

    @Test
    void movesWhenATransactionIsDeleted() {
        Transaction t = newTransaction("To be deleted");
        String before = stampOf(user);

        transactionRepository.delete(t);

        assertThat(stampOf(user)).isNotEqualTo(before);
    }

    @Test
    void movesWhenOneTransactionIsDeletedAndAnotherAddedAtOnce() {
        // Count and version-sum are both unchanged by this pair; only the newest created_at differs.
        Transaction t = newTransaction("Goes away");
        String before = stampOf(user);

        transactionRepository.delete(t);
        newTransaction("Arrives");

        assertThat(stampOf(user)).isNotEqualTo(before);
    }

    @Test
    void ignoresAnotherUsersActivity() {
        User other = newUser("change-stamp-other");
        String before = stampOf(user);

        newAccount(other, "Someone else's account");
        User reloaded = userRepository.findById(other.getId()).orElseThrow();
        reloaded.setFullName("Someone Else Renamed");
        userRepository.save(reloaded);

        assertThat(stampOf(user)).isEqualTo(before);
    }
}
