package com.finora.accounts;

import com.finora.entity.Account;
import com.finora.entity.StatementImport.BalanceApplicationMode;
import com.finora.repository.StatementImportRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AmountOwedRuleTest {

    private final StatementImportRepository repository = mock(StatementImportRepository.class);
    private final AmountOwedRule rule = new AmountOwedRule(repository);
    private final UUID userId = UUID.randomUUID();
    private final List<StatementImportRepository.ImportMode> modes = new ArrayList<>();

    AmountOwedRuleTest() {
        when(repository.findImportModesForUser(any())).thenReturn(modes);
    }

    private Account account(Account.Type type) {
        Account a = new Account();
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        a.setUserId(userId);
        a.setAccountType(type);
        return a;
    }

    private void imported(Account a, BalanceApplicationMode mode) {
        StatementImportRepository.ImportMode m = mock(StatementImportRepository.ImportMode.class);
        when(m.getAccountId()).thenReturn(a.getId());
        when(m.getBalanceApplicationMode()).thenReturn(mode);
        modes.add(m);
    }

    @Test
    void unknownWhenCardHasOnlyAdditiveImports() {
        Account card = account(Account.Type.CREDIT_CARD);
        imported(card, BalanceApplicationMode.ADDITIVE);
        imported(card, BalanceApplicationMode.COVERED);
        assertThat(rule.unknownCardIds(userId, List.of(card))).containsExactly(card.getId());
        assertThat(rule.isUnknown(card)).isTrue();
    }

    @Test
    void knownWithAnchor() {
        Account card = account(Account.Type.CREDIT_CARD);
        card.setLastAbsoluteSetStatementId(UUID.randomUUID());
        imported(card, BalanceApplicationMode.ADDITIVE);
        assertThat(rule.unknownCardIds(userId, List.of(card))).isEmpty();
    }

    @Test
    void knownWithTypedAt() {
        Account card = account(Account.Type.CREDIT_CARD);
        card.setBalanceTypedAt(Instant.now());
        imported(card, BalanceApplicationMode.ADDITIVE);
        assertThat(rule.unknownCardIds(userId, List.of(card))).isEmpty();
    }

    @Test
    void knownWithBaseline() {
        Account card = account(Account.Type.CREDIT_CARD);
        card.setBalanceBaselineDate(LocalDate.of(2025, 3, 31));
        imported(card, BalanceApplicationMode.ADDITIVE);
        assertThat(rule.unknownCardIds(userId, List.of(card))).isEmpty();
    }

    @Test
    void knownWithNoImports() {
        Account card = account(Account.Type.CREDIT_CARD);
        assertThat(rule.unknownCardIds(userId, List.of(card))).isEmpty();
        assertThat(rule.isUnknown(card)).isFalse();
    }

    @Test
    void knownWithAbsoluteImport() {
        Account card = account(Account.Type.CREDIT_CARD);
        imported(card, BalanceApplicationMode.ADDITIVE);
        imported(card, BalanceApplicationMode.ABSOLUTE);
        assertThat(rule.unknownCardIds(userId, List.of(card))).isEmpty();
    }

    @Test
    void knownWithLegacyImport() {
        Account card = account(Account.Type.CREDIT_CARD);
        imported(card, BalanceApplicationMode.UNKNOWN_LEGACY);
        assertThat(rule.unknownCardIds(userId, List.of(card))).isEmpty();
    }

    @Test
    void anotherAccountsImportsDoNotCount() {
        Account card = account(Account.Type.CREDIT_CARD);
        Account other = account(Account.Type.CREDIT_CARD);
        imported(other, BalanceApplicationMode.ADDITIVE);
        assertThat(rule.unknownCardIds(userId, List.of(card, other))).containsExactly(other.getId());
    }

    @Test
    void savingsNeverUnknown() {
        Account savings = account(Account.Type.SAVINGS);
        imported(savings, BalanceApplicationMode.ADDITIVE);
        assertThat(rule.unknownCardIds(userId, List.of(savings))).isEmpty();
    }

    @Test
    void noQueryWhenNoCandidateCard() {
        Account savings = account(Account.Type.SAVINGS);
        Account typedCard = account(Account.Type.CREDIT_CARD);
        typedCard.setBalanceTypedAt(Instant.now());
        assertThat(rule.unknownCardIds(userId, List.of(savings, typedCard))).isEmpty();
        assertThat(rule.isUnknown(typedCard)).isFalse();
        verifyNoInteractions(repository);
    }

    @Test
    void theResultAnswersContainsForAnUnsavedAccount() {
        Account unsaved = new Account();
        unsaved.setAccountType(Account.Type.SAVINGS);
        assertThat(rule.unknownCardIds(userId, List.of(unsaved)).contains(unsaved.getId())).isFalse();
    }
}
