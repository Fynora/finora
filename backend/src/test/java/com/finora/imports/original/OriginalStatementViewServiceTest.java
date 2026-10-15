package com.finora.imports.original;

import com.finora.exception.ApiException;
import com.finora.service.AdminHeldImportService;
import com.finora.service.HeldStatementService;
import com.finora.service.StatementStatusNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OriginalStatementViewServiceTest {

    @Mock AdminHeldImportService heldImports;
    @Mock HeldStatementService heldStatements;
    @Mock StatementOriginalViewRepository views;
    @Mock OriginalStatementRenderer renderer;
    @Mock ApplicationEventPublisher events;
    @Mock StatementStatusNotifier notifier;
    Clock clock = Clock.fixed(Instant.parse("2026-10-15T05:00:00Z"), ZoneOffset.UTC);
    OriginalStatementViewService service;

    UUID viewer = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    byte[] pdf = {1, 2, 3};
    String reason = "Rows on page 2 are missing from the preview";

    @BeforeEach
    void setUp() {
        service = new OriginalStatementViewService(heldImports, heldStatements, views, renderer, events, notifier, clock);
        lenient().when(views.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void openingAPdfSavesAViewAndPublishesTheEvent() {
        when(heldImports.contentForOriginalView(viewer, jobId, reason))
                .thenReturn(new OriginalContent(jobId, UUID.randomUUID(), "IMPORT", jobId.toString(), "PDF", pdf));
        when(renderer.pageCount(pdf)).thenReturn(4);

        OriginalView view = service.openImport(viewer, jobId, "  " + reason + "  ");

        assertThat(view.pageCount()).isEqualTo(4);
        assertThat(view.format()).isEqualTo("PDF");
        assertThat(view.expiresAt()).isEqualTo(Instant.parse("2026-10-15T05:30:00Z"));
        verify(views).save(argThat(v -> v.getViewerId().equals(viewer)
                && v.getQueue().equals("IMPORT") && v.getPageCount() == 4));
        // The trimmed reason travels with the event for the Super Admin email; the row keeps none.
        verify(events).publishEvent(argThat((Object e) -> e instanceof OriginalViewOpened o && reason.equals(o.reason())));
    }

    /** The customer is told in the opening's own transaction, so the notice and the view row commit
     *  together. 05:00 UTC on 15 Oct is 15 Oct in India. */
    @Test
    void openingTellsTheCustomerTheIndiaDate() {
        UUID owner = UUID.randomUUID();
        when(heldImports.contentForOriginalView(viewer, jobId, reason))
                .thenReturn(new OriginalContent(jobId, owner, "IMPORT", jobId.toString(), "PDF", pdf));
        when(renderer.pageCount(pdf)).thenReturn(1);

        service.openImport(viewer, jobId, reason);

        verify(notifier).notifyOpenedForFix(owner, jobId, java.time.LocalDate.parse("2026-10-15"));
        assertThat(OriginalStatementViewService.noticeDay(Instant.parse("2026-10-14T20:00:00Z")))
                .isEqualTo(java.time.LocalDate.parse("2026-10-15"));
        assertThat(OriginalStatementViewService.noticeDay(Instant.parse("2026-10-14T18:29:59Z")))
                .isEqualTo(java.time.LocalDate.parse("2026-10-14"));
    }

    @Test
    void openingATrustHoldUsesTheTrustQueue() {
        String heldId = "HLD-2026-810001";
        when(heldStatements.contentForOriginalView(viewer, heldId, reason))
                .thenReturn(new OriginalContent(jobId, UUID.randomUUID(), "TRUST", heldId, "PDF", pdf));
        when(renderer.pageCount(pdf)).thenReturn(2);

        OriginalView opened = service.openTrust(viewer, heldId, reason);

        verify(views).save(argThat(v -> v.getQueue().equals("TRUST") && v.getItemRef().equals(heldId)
                && v.getJobId().equals(jobId)));
        verify(events).publishEvent(any(OriginalViewOpened.class));
        verifyNoInteractions(heldImports);

        StatementOriginalView saved = StatementOriginalView.open(jobId, UUID.randomUUID(), viewer, "TRUST", heldId,
                "PDF", 2, clock.instant());
        when(views.findById(opened.viewId())).thenReturn(Optional.of(saved));
        when(heldStatements.contentForOpenView(heldId))
                .thenReturn(new OriginalContent(jobId, saved.getSubjectUserId(), "TRUST", heldId, "PDF", pdf));
        when(renderer.renderPage(pdf, 1)).thenReturn(new byte[] {7});

        assertThat(service.page(viewer, opened.viewId(), 1)).containsExactly(7);
        verifyNoInteractions(heldImports);
    }

    @Test
    void aCsvOpensWithNoPagesAndReturnsItsLines() {
        byte[] csv = "a,b\nc,d".getBytes();
        when(heldImports.contentForOriginalView(viewer, jobId, reason))
                .thenReturn(new OriginalContent(jobId, UUID.randomUUID(), "IMPORT", jobId.toString(), "CSV", csv));

        OriginalView opened = service.openImport(viewer, jobId, reason);

        assertThat(opened.format()).isEqualTo("CSV");
        assertThat(opened.pageCount()).isZero();
        verifyNoInteractions(renderer);

        StatementOriginalView saved = StatementOriginalView.open(jobId, UUID.randomUUID(), viewer, "IMPORT",
                jobId.toString(), "CSV", 0, clock.instant());
        when(views.findById(saved.getId())).thenReturn(Optional.of(saved));
        when(heldImports.contentForOpenView(jobId))
                .thenReturn(new OriginalContent(jobId, saved.getSubjectUserId(), "IMPORT", jobId.toString(), "CSV", csv));
        when(renderer.csvLines(csv)).thenReturn(List.of("a,b", "c,d"));

        assertThat(service.csv(viewer, saved.getId())).containsExactly("a,b", "c,d");
        assertThatThrownBy(() -> service.page(viewer, saved.getId(), 1))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void aShortReasonIsRefusedBeforeTheQueueIsAsked() {
        assertThatThrownBy(() -> service.openImport(viewer, jobId, "   too short   "))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(heldImports, views, events, notifier);
    }

    @Test
    void theReasonBoundsAreFifteenAndFiveHundredCharactersAfterTrimming() {
        assertThat(OriginalStatementViewService.validReason("  " + "x".repeat(15) + "  ")).hasSize(15);
        assertThat(OriginalStatementViewService.validReason("x".repeat(500))).hasSize(500);
        for (String bad : new String[] {null, "", "     ", "x".repeat(14), "x".repeat(501)}) {
            assertThatThrownBy(() -> OriginalStatementViewService.validReason(bad))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getMessage()).isEqualTo("Type a reason of 15 to 500 characters.");
                    });
        }
        assertThatThrownBy(() -> service.openTrust(viewer, "HLD-2026-810002", "     "))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(heldStatements, views, events, notifier);
    }

    /** The reason lands in an audit entry and a Super Admin's inbox: control characters are
     *  removed, and bidirectional overrides too, which make text display differently from how it is
     *  stored. Line breaks stay. The length rule counts what is left. */
    @Test
    void theReasonIsCleanedOfControlAndBidiCharactersBeforeItIsCounted() {
        assertThat(OriginalStatementViewService.validReason("Rows on page 2\u202E are\u0007 missing\nagain"))
                .isEqualTo("Rows on page 2 are missing\nagain");
        assertThatThrownBy(() -> OriginalStatementViewService.validReason("\u202E\u202E\u202E\u202E\u202E short words"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void noNoticeWhenTheReadFails() {
        when(heldImports.contentForOriginalView(viewer, jobId, reason))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "could not be read"));
        assertThatThrownBy(() -> service.openImport(viewer, jobId, reason)).isInstanceOf(ApiException.class);
        verifyNoInteractions(views, events, notifier);
    }

    @Test
    void noNoticeWhenThePdfCannotBeRendered() {
        when(heldImports.contentForOriginalView(viewer, jobId, reason))
                .thenReturn(new OriginalContent(jobId, UUID.randomUUID(), "IMPORT", jobId.toString(), "PDF", pdf));
        when(renderer.pageCount(pdf)).thenThrow(new ApiException(HttpStatus.CONFLICT, "could not be opened"));
        assertThatThrownBy(() -> service.openImport(viewer, jobId, reason)).isInstanceOf(ApiException.class);
        verifyNoInteractions(views, events, notifier);
    }

    @Test
    void anotherViewersIdIsNotFound() {
        StatementOriginalView v = StatementOriginalView.open(jobId, UUID.randomUUID(), viewer, "IMPORT",
                jobId.toString(), "PDF", 2, clock.instant());
        when(views.findById(v.getId())).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> service.page(UUID.randomUUID(), v.getId(), 1))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.csv(UUID.randomUUID(), v.getId()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(heldImports, renderer);
    }

    @Test
    void anUnknownViewIdIsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(views.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.page(viewer, unknown, 1))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void anExpiredViewIsNotFound() {
        StatementOriginalView v = StatementOriginalView.open(jobId, UUID.randomUUID(), viewer, "IMPORT",
                jobId.toString(), "PDF", 2, clock.instant().minus(Duration.ofMinutes(31)));
        when(views.findById(v.getId())).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> service.page(viewer, v.getId(), 1))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(heldImports, renderer);
    }

    @Test
    void aPageIsRenderedFromFreshContentForItsOwnQueue() {
        StatementOriginalView v = StatementOriginalView.open(jobId, UUID.randomUUID(), viewer, "IMPORT",
                jobId.toString(), "PDF", 2, clock.instant());
        when(views.findById(v.getId())).thenReturn(Optional.of(v));
        when(heldImports.contentForOpenView(jobId))
                .thenReturn(new OriginalContent(jobId, v.getSubjectUserId(), "IMPORT", jobId.toString(), "PDF", pdf));
        when(renderer.renderPage(pdf, 2)).thenReturn(new byte[] {9});

        assertThat(service.page(viewer, v.getId(), 2)).containsExactly(9);
        verifyNoInteractions(heldStatements);
    }

    @Test
    void askingForCsvLinesOfAPdfViewIsAConflict() {
        StatementOriginalView v = StatementOriginalView.open(jobId, UUID.randomUUID(), viewer, "IMPORT",
                jobId.toString(), "PDF", 2, clock.instant());
        when(views.findById(v.getId())).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> service.csv(viewer, v.getId()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }
}
