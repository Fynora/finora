package com.finora.imports.refresh;

import com.finora.config.BuildVersionResolver;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.entity.StatementImport;
import com.finora.entity.StatementRefreshPreview;
import com.finora.imports.ImportService;
import com.finora.imports.RowKind;
import com.finora.imports.storage.StatementContentService;
import com.finora.repository.StatementImportExcludedRowRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.StatementRefreshPreviewRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StatementRefreshDryRunServiceTest {

    private final StatementImportRepository statements = mock(StatementImportRepository.class);
    private final TransactionRepository transactions = mock(TransactionRepository.class);
    private final StatementImportExcludedRowRepository excluded = mock(StatementImportExcludedRowRepository.class);
    private final StatementRefreshPreviewRepository previews = mock(StatementRefreshPreviewRepository.class);
    private final StatementContentService content = mock(StatementContentService.class);
    private final ImportService importService = mock(ImportService.class);
    private final BuildVersionResolver build = mock(BuildVersionResolver.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final List<Runnable> handedOff = new ArrayList<>();

    private StatementRefreshDryRunService service() {
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        StatementRefreshDryRunService s = new StatementRefreshDryRunService(statements,
                new StatementRefreshInputs(transactions, excluded, mock(com.finora.repository.AccountRepository.class)), previews,
                content, importService, build, txManager, handedOff::add);
        ReflectionTestUtils.setField(s, "enabled", true);
        ReflectionTestUtils.setField(s, "batchSize", 20);
        return s;
    }

    private StatementImport statement(UUID id) {
        StatementImport s = new StatementImport();
        ReflectionTestUtils.setField(s, "id", id);
        s.setUserId(UUID.randomUUID());
        s.setSourceFormat("CSV");
        s.setFileName("statement.csv");
        return s;
    }

    @Test
    void theScheduledTick_onlyHandsTheBatchOff_neverRunsItOnTheSchedulerThread() {
        StatementRefreshDryRunService s = service();

        s.scheduledRun();

        assertThat(handedOff).hasSize(1);
        verifyNoInteractions(statements, previews, importService);
    }

    @Test
    void anUnexpectedErrorWhileComparing_isRecordedAsFailed_soTheStatementIsNotRetriedEveryTick() throws Exception {
        UUID id = UUID.randomUUID();
        StatementImport st = statement(id);
        when(build.currentCommit()).thenReturn("build1");
        when(statements.findIdsAwaitingRefreshCheck(eq("build1"), anyInt())).thenReturn(List.of(id));
        when(statements.findById(id)).thenReturn(Optional.of(st));
        when(statements.findByIdForUpdate(id)).thenReturn(Optional.of(st));
        when(content.read(any())).thenReturn(new byte[]{1});
        when(importService.parseAndStageAnyFormat(any(), any(), any(), any(), any(), any())).thenReturn(
                new StagingResponse(List.of(new StagedRow(LocalDate.of(2026, 7, 1), "ROW", new BigDecimal("1.00"),
                        "EXPENSE", "Other", "rule", null, false, null, null, null, RowKind.TRANSACTION, null, null, null, null)),
                        1, 0, null, List.of()));
        when(transactions.findStatementRowsIncludingDeleted(any(), eq(id))).thenThrow(new IllegalStateException("boom"));

        var result = service().runBatch(20);

        assertThat(result.checked()).isZero();
        ArgumentCaptor<StatementRefreshPreview> saved = ArgumentCaptor.forClass(StatementRefreshPreview.class);
        verify(previews).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(StatementRefreshPreview.Status.FAILED);
        assertThat(saved.getValue().getDetail()).containsEntry("reason", "UNEXPECTED_IllegalStateException");
        assertThat(saved.getValue().getParserVersion()).isEqualTo("build1");
    }

    @Test
    void aSupersededStatement_isNeverChecked() throws Exception {
        UUID id = UUID.randomUUID();
        StatementImport st = statement(id);
        st.setSupersededBy(UUID.randomUUID());
        when(statements.findById(id)).thenReturn(Optional.of(st));
        when(statements.findByIdForUpdate(id)).thenReturn(Optional.of(st));

        service().check(id, "build1");

        verifyNoInteractions(importService);
        verify(previews, never()).save(any());
    }

    @Test
    void withNoBuildVersion_nothingIsChecked() {
        when(build.currentCommit()).thenReturn(null);

        assertThat(service().runBatch(20).checked()).isZero();
        verifyNoInteractions(previews);
    }
}
