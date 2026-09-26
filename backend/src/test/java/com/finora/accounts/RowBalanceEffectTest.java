package com.finora.accounts;

import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.repository.StatementImportRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RowBalanceEffectTest {

    private final StatementImportRepository repository = mock(StatementImportRepository.class);
    private final RowBalanceEffect effect = new RowBalanceEffect(repository);
    private final Account account = new Account();

    RowBalanceEffectTest() {
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        account.setUserId(UUID.randomUUID());
    }

    private StatementImportRepository.AnchorSnapshot link(UUID id, Instant importedAt, UUID previous) {
        StatementImportRepository.AnchorSnapshot s = mock(StatementImportRepository.AnchorSnapshot.class);
        when(s.getId()).thenReturn(id);
        when(s.getImportedAt()).thenReturn(importedAt);
        when(s.getPreviousAbsoluteSetStatementId()).thenReturn(previous);
        when(s.getBalanceBeforeAbsoluteSet()).thenReturn(new BigDecimal("100.00"));
        when(repository.findAnchorSnapshotIncludingDeleted(any(), any(), eq(id))).thenReturn(Optional.of(s));
        return s;
    }

    private Transaction manualRow(Instant createdAt) {
        Transaction t = new Transaction();
        t.setSource(Transaction.Source.MANUAL);
        ReflectionTestUtils.setField(t, "createdAt", createdAt);
        return t;
    }

    @Test
    void aRowIsHeldByTheEarliestSetThatCameAfterIt_andEachLinkIsReadOncePerChain() {
        UUID march = UUID.randomUUID(), april = UUID.randomUUID(), may = UUID.randomUUID();
        link(march, Instant.parse("2026-04-01T00:00:00Z"), null);
        link(april, Instant.parse("2026-05-01T00:00:00Z"), march);
        link(may, Instant.parse("2026-06-01T00:00:00Z"), april);
        account.setLastAbsoluteSetStatementId(may);

        RowBalanceEffect.Chain chain = effect.chainOf(account);
        assertThat(effect.locate(account, manualRow(Instant.parse("2026-06-15T00:00:00Z")), null, chain))
                .as("arrived after the live SET: in the balance itself")
                .isEqualTo(new RowBalanceEffect.Location(RowBalanceEffect.Where.BALANCE, null));
        assertThat(effect.locate(account, manualRow(Instant.parse("2026-05-15T00:00:00Z")), null, chain))
                .isEqualTo(new RowBalanceEffect.Location(RowBalanceEffect.Where.SNAPSHOT, may));
        assertThat(effect.locate(account, manualRow(Instant.parse("2026-03-15T00:00:00Z")), null, chain))
                .as("older than every SET: the oldest one's snapshot holds it")
                .isEqualTo(new RowBalanceEffect.Location(RowBalanceEffect.Where.SNAPSHOT, march));
        for (int i = 0; i < 10; i++) {
            effect.locate(account, manualRow(Instant.parse("2026-03-15T00:00:00Z")), null, chain);
        }

        verify(repository, times(1)).findAnchorSnapshotIncludingDeleted(any(), any(), eq(may));
        verify(repository, times(1)).findAnchorSnapshotIncludingDeleted(any(), any(), eq(april));
        verify(repository, times(1)).findAnchorSnapshotIncludingDeleted(any(), any(), eq(march));
    }

    @Test
    void aRowFromBeforeTheBalanceWasTyped_isNowhere_andAnAggregatorRowIsNowhere() {
        account.setBalanceTypedAt(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(effect.locate(account, manualRow(Instant.parse("2026-08-01T00:00:00Z")), null).where())
                .isEqualTo(RowBalanceEffect.Where.NOWHERE);
        assertThat(effect.locate(account, manualRow(Instant.parse("2026-09-02T00:00:00Z")), null).where())
                .isEqualTo(RowBalanceEffect.Where.BALANCE);

        Transaction aggregator = manualRow(Instant.parse("2026-09-02T00:00:00Z"));
        aggregator.setSource(Transaction.Source.ACCOUNT_AGGREGATOR);
        assertThat(effect.locate(account, aggregator, null).where()).isEqualTo(RowBalanceEffect.Where.NOWHERE);
    }

    @Test
    void aStatementsModeDecides_legacyStatementsKeepMovingTheBalance() {
        StatementImport statement = new StatementImport();
        Transaction row = manualRow(Instant.parse("2026-09-02T00:00:00Z"));
        row.setSource(Transaction.Source.CSV_IMPORT);

        statement.setBalanceApplicationMode(StatementImport.BalanceApplicationMode.ABSOLUTE);
        assertThat(effect.locate(account, row, statement).where()).isEqualTo(RowBalanceEffect.Where.NOWHERE);
        statement.setBalanceApplicationMode(StatementImport.BalanceApplicationMode.COVERED);
        assertThat(effect.locate(account, row, statement).where()).isEqualTo(RowBalanceEffect.Where.NOWHERE);
        statement.setBalanceApplicationMode(StatementImport.BalanceApplicationMode.UNKNOWN_LEGACY);
        assertThat(effect.locate(account, row, statement).where())
                .as("whether a legacy statement's rows moved the balance was never recorded; not guessed")
                .isEqualTo(RowBalanceEffect.Where.BALANCE);
        statement.setBalanceApplicationMode(StatementImport.BalanceApplicationMode.ADDITIVE);
        assertThat(effect.locate(account, row, statement).where()).isEqualTo(RowBalanceEffect.Where.BALANCE);

        row.setIsDuplicateOf(UUID.randomUUID());
        row.setDuplicateBalanceReversed(true);
        assertThat(effect.locate(account, row, statement).where())
                .as("the mark already took it off")
                .isEqualTo(RowBalanceEffect.Where.NOWHERE);
    }
}
