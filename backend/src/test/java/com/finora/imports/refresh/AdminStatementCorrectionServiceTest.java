package com.finora.imports.refresh;

import com.finora.entity.StatementImport;
import com.finora.exception.ApiException;
import com.finora.notification.api.NotificationService;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.StatementRefreshPreviewRepository.PendingStatement;
import com.finora.service.AuditService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The path the integration test cannot reach: the statement goes away between the check and the apply. */
class AdminStatementCorrectionServiceTest {

    private final StatementRefreshUserService userService = mock(StatementRefreshUserService.class);
    private final StatementRefreshService refreshService = mock(StatementRefreshService.class);
    private final StatementImportRepository statements = mock(StatementImportRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final AdminStatementCorrectionService service =
            new AdminStatementCorrectionService(userService, refreshService, statements, audit, notifications);

    @Test
    @SuppressWarnings("unchecked")
    void aRefreshThatThrowsAfterTheRequestIsRecordedAsNotApplied_andTheUserIsNotTold() {
        UUID admin = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID statementId = UUID.randomUUID();
        StatementImport statement = mock(StatementImport.class);
        when(statement.getUserId()).thenReturn(owner);
        when(statements.findById(statementId)).thenReturn(Optional.of(statement));
        PendingStatement pending = mock(PendingStatement.class);
        when(pending.getStatementImportId()).thenReturn(statementId);
        when(userService.correctionsReady(owner)).thenReturn(List.of(pending));
        when(refreshService.refreshForOwnerByAdmin(owner, statementId, admin))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "This statement was deleted or replaced"));

        assertThatThrownBy(() -> service.apply(admin, statementId, "Applying the checked card correction"))
                .isInstanceOf(ApiException.class);

        ArgumentCaptor<String> actions = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(audit, times(2)).record(eq(owner), actions.capture(), eq("StatementImport"), eq(statementId),
                metadata.capture());
        assertThat(actions.getAllValues()).containsExactly(
                "STATEMENT_CORRECTION_REQUESTED_BY_ADMIN", "STATEMENT_CORRECTION_APPLIED_BY_ADMIN");
        assertThat(metadata.getAllValues().get(0)).containsEntry("actorId", admin.toString())
                .containsEntry("reason", "Applying the checked card correction");
        assertThat(metadata.getAllValues().get(1)).containsEntry("actorId", admin.toString())
                .containsEntry("status", "NOT_APPLIED").containsEntry("error", "ApiException");
        verify(notifications, never()).request(any());
    }

    @Test
    void aStatementWithNoCheckedCorrectionIsRefusedBeforeAnythingIsRecordedOrRead() {
        UUID owner = UUID.randomUUID();
        UUID statementId = UUID.randomUUID();
        StatementImport statement = mock(StatementImport.class);
        when(statement.getUserId()).thenReturn(owner);
        when(statements.findById(statementId)).thenReturn(Optional.of(statement));
        when(userService.correctionsReady(owner)).thenReturn(List.of());

        assertThatThrownBy(() -> service.apply(UUID.randomUUID(), statementId, "Applying the checked card correction"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(audit, never()).record(any(), anyString(), anyString(), any(), any());
        verify(refreshService, never()).refreshForOwnerByAdmin(any(), any(), any());
    }

    @Test
    void anUnknownStatementIsNotFound() {
        when(statements.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.apply(UUID.randomUUID(), UUID.randomUUID(), "Applying the checked card correction"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
