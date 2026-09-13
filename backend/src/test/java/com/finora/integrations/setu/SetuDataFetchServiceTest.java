package com.finora.integrations.setu;

import com.finora.entity.FeatureEntitlement;
import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import com.finora.service.AuditService;
import com.finora.service.EntitlementService;
import com.finora.service.ReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SetuDataFetchServiceTest {

    private SetuDataFetchGateway gateway;
    private AccountAggregatorTransactionDiffService diffService;
    private TransactionRepository transactionRepository;
    private AccountAggregatorLinkRepository links;
    private EntitlementService entitlementService;
    private AuditService auditService;
    private ReconciliationService reconciliationService;
    private SetuDataFetchService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private AccountAggregatorLink link;

    @BeforeEach
    void setUp() {
        gateway = mock(SetuDataFetchGateway.class);
        diffService = mock(AccountAggregatorTransactionDiffService.class);
        transactionRepository = mock(TransactionRepository.class);
        links = mock(AccountAggregatorLinkRepository.class);
        entitlementService = mock(EntitlementService.class);
        auditService = mock(AuditService.class);
        reconciliationService = mock(ReconciliationService.class);
        // 14 matches application.yml's own default -- passed explicitly (constructor injection,
        // not a field-level @Value) since this plain `new` call never goes through Spring.
        service = new SetuDataFetchService(gateway, diffService, transactionRepository, links,
                entitlementService, auditService, reconciliationService, 14);

        link = new AccountAggregatorLink();
        link.setUserId(userId);
        link.setAccountId(accountId);
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setConsentHandleId("consent-handle-1");
        ReflectionTestUtils.setField(link, "id", UUID.randomUUID());

        when(entitlementService.hasEntitlement(userId, FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC))
                .thenReturn(true);
        when(links.save(any(AccountAggregatorLink.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void fetchesMapsPersistsAndReconciles() {
        SetuFiDataTransaction raw = new SetuFiDataTransaction("txn-1", "DEBIT", new BigDecimal("100"),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1), "desc", new BigDecimal("900"), "ref");
        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.fetchTransactions("consent-handle-1", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 1)))
                .thenReturn(new SetuFiDataFetchResult("XXXX1234", new BigDecimal("900"), List.of(raw)));
        Transaction mapped = new Transaction();
        when(diffService.diff(userId, accountId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 1), List.of(raw)))
                .thenReturn(new AccountAggregatorTransactionDiffService.DiffResult(List.of(mapped), 0, 0));

        service.sync(link, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 1));

        verify(transactionRepository).saveAll(List.of(mapped));
        verify(reconciliationService).reconcileForImport(userId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 1));
        assertThat(link.getLastSyncStatus()).isEqualTo(AccountAggregatorLink.SyncStatus.SUCCESS);
        assertThat(link.getLastSyncedAt()).isNotNull();
        verify(links).save(link);
    }

    @Test
    void doesNotReconcileWhenNothingNewWasIngested() {
        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.fetchTransactions(any(), any(), any()))
                .thenReturn(new SetuFiDataFetchResult("XXXX1234", new BigDecimal("900"), List.of()));
        when(diffService.diff(any(), any(), any(), any(), any()))
                .thenReturn(new AccountAggregatorTransactionDiffService.DiffResult(List.of(), 0, 0));

        service.sync(link, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 1));

        verify(transactionRepository, never()).saveAll(any());
        verify(reconciliationService, never()).reconcileForImport(any(), any(), any());
        assertThat(link.getLastSyncStatus()).isEqualTo(AccountAggregatorLink.SyncStatus.SUCCESS);
    }

    @Test
    void marksTheLinkFailedWhenTheGatewayThrows() {
        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.fetchTransactions(any(), any(), any())).thenThrow(new RuntimeException("Setu 500"));

        service.sync(link, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 1));

        assertThat(link.getLastSyncStatus()).isEqualTo(AccountAggregatorLink.SyncStatus.FAILED);
        verify(transactionRepository, never()).saveAll(any());
        verify(auditService).record(eq(userId), eq("ACCOUNT_AGGREGATOR_SYNC_FAILED"),
                eq("AccountAggregatorLink"), any());
    }

    @Test
    void reconciliationFailureDoesNotOverwriteASuccessfulPersist() {
        // Regression test: the original version wrapped reconcileForImport in the same try/catch
        // as the fetch+persist -- a reconciliation crash AFTER a successful save still marked the
        // whole sync FAILED, even though the transactions were already safely in the ledger.
        SetuFiDataTransaction raw = new SetuFiDataTransaction("txn-1", "DEBIT", new BigDecimal("100"),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1), "desc", new BigDecimal("900"), "ref");
        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.fetchTransactions("consent-handle-1", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 1)))
                .thenReturn(new SetuFiDataFetchResult("XXXX1234", new BigDecimal("900"), List.of(raw)));
        Transaction mapped = new Transaction();
        when(diffService.diff(userId, accountId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 1), List.of(raw)))
                .thenReturn(new AccountAggregatorTransactionDiffService.DiffResult(List.of(mapped), 0, 0));
        doThrow(new RuntimeException("reconciliation exploded"))
                .when(reconciliationService).reconcileForImport(any(), any(), any());

        service.sync(link, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 1));

        verify(transactionRepository).saveAll(List.of(mapped));
        assertThat(link.getLastSyncStatus()).isEqualTo(AccountAggregatorLink.SyncStatus.SUCCESS);
        verify(auditService, never()).record(any(), eq("ACCOUNT_AGGREGATOR_SYNC_FAILED"), any(), any());
    }

    @Test
    void skipsTheFetchEntirelyWhenTheUserIsNoLongerEntitled() {
        when(entitlementService.hasEntitlement(userId, FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC))
                .thenReturn(false);

        service.sync(link, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 1));

        verifyNoInteractions(gateway);
        verify(links, never()).save(any());
    }

    @Test
    void skipsWhenTheGatewayItselfIsNotConfigured() {
        when(gateway.isConfigured()).thenReturn(false);

        service.sync(link, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 1));

        verify(gateway, never()).fetchTransactions(any(), any(), any());
        verify(links, never()).save(any());
    }
}
