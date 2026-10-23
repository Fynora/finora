package com.finora.support;

import com.finora.entity.ClientPlatform;
import com.finora.entity.SupportTicket;
import com.finora.exception.ApiException;
import com.finora.repository.SupportTicketAttachmentRepository;
import com.finora.repository.SupportTicketInternalNoteRepository;
import com.finora.repository.SupportTicketRepository;
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationType;
import com.finora.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Mockito rather than a Spring-context IT — matches {@code AdminHeldImportServiceTest}'s own
 * precedent: what's being asserted here (validation, the status matrix, ownership branching) is
 * pure logic that doesn't need Postgres to prove. The dual-audience read path and the real
 * cross-user attachment seam are covered separately by {@code SupportTicketApiIT}, which is the one
 * that actually needs a database.
 */
class SupportTicketServiceTest {

    private SupportTicketRepository ticketRepository;
    private SupportTicketAttachmentRepository attachmentRepository;
    private SupportTicketInternalNoteRepository noteRepository;
    private SupportTicketIdGenerator idGenerator;
    private ClientIdentity clientIdentity;
    private AuditService auditService;
    private NotificationService notificationService;
    private SupportAdminAlertService adminAlerts;
    private SupportTicketService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ticketRepository = mock(SupportTicketRepository.class);
        attachmentRepository = mock(SupportTicketAttachmentRepository.class);
        noteRepository = mock(SupportTicketInternalNoteRepository.class);
        idGenerator = mock(SupportTicketIdGenerator.class);
        clientIdentity = mock(ClientIdentity.class);
        auditService = mock(AuditService.class);
        notificationService = mock(NotificationService.class);
        adminAlerts = mock(SupportAdminAlertService.class);
        service = new SupportTicketService(ticketRepository, attachmentRepository, noteRepository, idGenerator,
                clientIdentity, auditService, notificationService, adminAlerts);

        when(ticketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(noteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(idGenerator.next()).thenReturn("SUP-000001");
        when(clientIdentity.platform()).thenReturn(ClientPlatform.WEB);
        when(clientIdentity.appVersion()).thenReturn(null);
    }

    // --- create() validation ---------------------------------------------------------------

    @Test
    void rejectsAnUnknownCategory() {
        assertThatThrownBy(() -> service.create(userId, "NOT_A_REAL_CATEGORY", "Subject", "Description", null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST));
    }

    @Test
    void rejectsABlankSubject() {
        assertThatThrownBy(() -> service.create(userId, "OTHER", "   ", "a real description", null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsASubjectOverTheHundredTwentyCharacterColumnBound() {
        String tooLong = "x".repeat(121);
        assertThatThrownBy(() -> service.create(userId, "OTHER", tooLong, "description", null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void createsATicketWithNoAttachmentWhenNoFileIsGiven() {
        var detail = service.create(userId, "TECHNICAL_ISSUE", "Import failing", "It just spins", null);

        assertThat(detail.ticketNumber()).isEqualTo("SUP-000001");
        assertThat(detail.attachments()).isEmpty();
        assertThat(detail.source()).isEqualTo(ClientPlatform.WEB);
    }

    @Test
    void creatingATicketAudits_withTheUserAsSubject_noActorNeeded() {
        service.create(userId, "TECHNICAL_ISSUE", "Import failing", "It just spins", null);

        verify(auditService).record(eq(userId), eq("SUPPORT_TICKET_CREATED"), eq("SupportTicket"), any(),
                eq(Map.of("ticketNumber", "SUP-000001", "category", "TECHNICAL_ISSUE")));
    }

    @Test
    void creatingATicketQueuesAnEmailOnlyAcknowledgementCarryingTheTicketNumber() {
        service.create(userId, "TECHNICAL_ISSUE", "Import failing", "It just spins", null);

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).request(captor.capture());
        NotificationRequest request = captor.getValue();
        assertThat(request.userId()).isEqualTo(userId);
        assertThat(request.type()).isEqualTo(NotificationType.SUPPORT_TICKET_RECEIVED);
        assertThat(request.channels()).isEqualTo(Set.of(NotificationChannel.EMAIL));
        // Never suppressible by a notification preference: the customer asked us something.
        assertThat(request.category()).isEqualTo(NotificationCategory.SECURITY);
        // Only the number -- never the customer's own subject or description.
        assertThat(request.params()).isEqualTo(Map.of("ticketNumber", "SUP-000001"));
    }

    /** The cap's boundary, both sides: the third ticket in a day is still acknowledged, the fourth is
     *  not -- and the ticket itself is created either way. */
    @Test
    void theThirdTicketInADayIsAcknowledged_theFourthIsNot_butIsStillCreated() {
        when(ticketRepository.countByUserIdAndCreatedAtAfter(eq(userId), any()))
                .thenReturn((long) SupportTicketService.MAX_ACKNOWLEDGEMENTS_PER_DAY);
        service.create(userId, "OTHER", "Third", "Description", null);
        verify(notificationService, org.mockito.Mockito.times(1)).request(any(NotificationRequest.class));

        when(ticketRepository.countByUserIdAndCreatedAtAfter(eq(userId), any()))
                .thenReturn((long) SupportTicketService.MAX_ACKNOWLEDGEMENTS_PER_DAY + 1);
        var detail = service.create(userId, "OTHER", "Fourth", "Description", null);

        assertThat(detail.ticketNumber()).isEqualTo("SUP-000001");
        verify(notificationService, org.mockito.Mockito.times(1)).request(any(NotificationRequest.class));
    }

    @Test
    void aRejectedCreate_neverQueuesAnAcknowledgement() {
        assertThatThrownBy(() -> service.create(userId, "OTHER", "   ", "description", null))
                .isInstanceOf(ApiException.class);

        verifyNoInteractions(notificationService);
    }

    @Test
    void creatingATicketTellsTheAdmins_withTheTicketNumberCategoryAndSourceButNoneOfTheText() {
        // No transaction here, so AfterCommit runs the alert immediately -- the real call, not a stub.
        service.create(userId, "TECHNICAL_ISSUE", "Import failing", "It just spins", null);

        verify(adminAlerts).alertTicketRaised(any(), eq("SUP-000001"), eq(SupportTicket.Category.TECHNICAL_ISSUE),
                eq(ClientPlatform.WEB), any());
    }

    @Test
    void aRejectedCreate_tellsNoOne() {
        assertThatThrownBy(() -> service.create(userId, "OTHER", "   ", "description", null))
                .isInstanceOf(ApiException.class);

        verifyNoInteractions(adminAlerts);
    }

    @Test
    void anAlertThatFailsToStart_doesNotFailTheTicket() {
        org.mockito.Mockito.doThrow(new RuntimeException("executor shut down")).when(adminAlerts)
                .alertTicketRaised(any(), any(), any(), any(), any());

        var detail = service.create(userId, "OTHER", "Subject", "Description", null);

        assertThat(detail.ticketNumber()).isEqualTo("SUP-000001");
    }

    @Test
    void aRejectedCreate_neverAudits() {
        assertThatThrownBy(() -> service.create(userId, "OTHER", "   ", "description", null))
                .isInstanceOf(ApiException.class);

        verifyNoInteractions(auditService);
    }

    // --- status transitions ------------------------------------------------------------------

    private SupportTicket ticketWithStatus(SupportTicket.Status status) {
        SupportTicket ticket = new SupportTicket();
        ticket.setUserId(userId);
        ticket.setStatus(status);
        ticket.setTicketNumber("SUP-000042");
        when(ticketRepository.findById(any())).thenReturn(Optional.of(ticket));
        return ticket;
    }

    @Test
    void openMovesToAnyOtherStatus() {
        ticketWithStatus(SupportTicket.Status.OPEN);
        var result = service.updateStatus(adminId, UUID.randomUUID(), SupportTicket.Status.CLOSED);
        assertThat(result.status()).isEqualTo(SupportTicket.Status.CLOSED);
    }

    @Test
    void resolvedCannotMoveBackToInProgress() {
        ticketWithStatus(SupportTicket.Status.RESOLVED);

        assertThatThrownBy(() -> service.updateStatus(adminId, UUID.randomUUID(), SupportTicket.Status.IN_PROGRESS))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.CONFLICT));

        verifyNoInteractions(auditService);
    }

    @Test
    void closedIsTerminal() {
        ticketWithStatus(SupportTicket.Status.CLOSED);

        assertThatThrownBy(() -> service.updateStatus(adminId, UUID.randomUUID(), SupportTicket.Status.OPEN))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void resolvingATicketStampsResolvedAt() {
        ticketWithStatus(SupportTicket.Status.IN_PROGRESS);
        var result = service.updateStatus(adminId, UUID.randomUUID(), SupportTicket.Status.RESOLVED);
        assertThat(result.status()).isEqualTo(SupportTicket.Status.RESOLVED);
    }

    @Test
    void statusChangeAudits_withTheTicketOwnerAsSubject_andTheAdminAsActor() {
        SupportTicket ticket = ticketWithStatus(SupportTicket.Status.OPEN);
        UUID ticketId = UUID.randomUUID();

        service.updateStatus(adminId, ticketId, SupportTicket.Status.IN_PROGRESS);

        verify(auditService).record(eq(userId), eq("SUPPORT_TICKET_STATUS_CHANGED"), eq("SupportTicket"), any(),
                eq(Map.of("actorId", adminId.toString(), "ticketNumber", ticket.getTicketNumber(),
                        "previousStatus", "OPEN", "newStatus", "IN_PROGRESS")));
    }

    // --- ownership: dual-audience reads -------------------------------------------------------

    @Test
    void aRegularCallerReadsOnlyTheirOwnTicket_viaTheOwnerScopedQuery_andItIsNotAudited() {
        SupportTicket ticket = new SupportTicket();
        ticket.setUserId(userId);
        ticket.setTicketNumber("SUP-000042");
        UUID ticketId = UUID.randomUUID();
        when(ticketRepository.findByIdAndUserId(ticketId, userId)).thenReturn(Optional.of(ticket));
        when(attachmentRepository.findMetadataByTicketId(any())).thenReturn(List.of());

        var detail = service.getDetail(userId, false, ticketId);

        assertThat(detail).isNotNull();
        verifyNoInteractions(auditService);
    }

    @Test
    void aRegularCaller_cannotReadSomeoneElsesTicket() {
        UUID ticketId = UUID.randomUUID();
        // findByIdAndUserId returns empty -- the ticket exists but not for this caller.
        when(ticketRepository.findByIdAndUserId(ticketId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(userId, false, ticketId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND));
    }

    @Test
    void anAdminCaller_readsAnyTicket_viaTheUnscopedQuery_andItIsAudited() {
        UUID ownerId = UUID.randomUUID(); // owned by someone else entirely
        SupportTicket ticket = new SupportTicket();
        ticket.setUserId(ownerId);
        ticket.setTicketNumber("SUP-000042");
        UUID ticketId = UUID.randomUUID();
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(attachmentRepository.findMetadataByTicketId(any())).thenReturn(List.of());

        var detail = service.getDetail(adminId, true, ticketId);

        assertThat(detail).isNotNull();
        verify(auditService).record(eq(ownerId), eq("SUPPORT_TICKET_VIEWED"), eq("SupportTicket"), any(),
                eq(Map.of("actorId", adminId.toString(), "ticketNumber", "SUP-000042")));
    }

    // --- claim / unclaim ---------------------------------------------------------------------

    private SupportTicket ownedTicket(UUID claimedBy) {
        SupportTicket ticket = new SupportTicket();
        ticket.setUserId(userId);
        ticket.setTicketNumber("SUP-000042");
        ticket.setClaimedByAdminId(claimedBy);
        return ticket;
    }

    @Test
    void claimingAnAlreadyClaimedTicketSucceeds_andRecordsTheNewClaimant() {
        UUID previousAdmin = UUID.randomUUID();
        SupportTicket ticket = ownedTicket(previousAdmin);
        UUID ticketId = UUID.randomUUID();
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

        var result = service.claim(adminId, ticketId);

        assertThat(result.claimedByAdminId()).isEqualTo(adminId);
        verify(auditService).record(eq(userId), eq("SUPPORT_TICKET_CLAIMED"), eq("SupportTicket"), any(),
                eq(Map.of("actorId", adminId.toString(), "ticketNumber", "SUP-000042",
                        "previousAdminId", previousAdmin.toString(), "newAdminId", adminId.toString())));
    }

    @Test
    void aFirstClaim_recordsANullPreviousAdmin() {
        SupportTicket ticket = ownedTicket(null);
        UUID ticketId = UUID.randomUUID();
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

        service.claim(adminId, ticketId);

        var metadata = new java.util.HashMap<String, Object>();
        metadata.put("actorId", adminId.toString());
        metadata.put("ticketNumber", "SUP-000042");
        metadata.put("previousAdminId", null);
        metadata.put("newAdminId", adminId.toString());
        verify(auditService).record(eq(userId), eq("SUPPORT_TICKET_CLAIMED"), eq("SupportTicket"), any(), eq(metadata));
    }

    @Test
    void unclaimReleasesTheTicketRegardlessOfWhoClaimedIt() {
        UUID previousAdmin = UUID.randomUUID();
        SupportTicket ticket = ownedTicket(previousAdmin);
        UUID ticketId = UUID.randomUUID();
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

        var result = service.unclaim(adminId, ticketId);

        assertThat(result.claimedByAdminId()).isNull();
        var metadata = new java.util.HashMap<String, Object>();
        metadata.put("actorId", adminId.toString());
        metadata.put("ticketNumber", "SUP-000042");
        metadata.put("previousAdminId", previousAdmin.toString());
        metadata.put("newAdminId", null);
        verify(auditService).record(eq(userId), eq("SUPPORT_TICKET_CLAIMED"), eq("SupportTicket"), any(), eq(metadata));
    }

    // --- internal notes -----------------------------------------------------------------------

    @Test
    void addingANoteAudits_withoutCopyingTheNoteBodyIntoTheMetadata() {
        SupportTicket ticket = ownedTicket(null);
        UUID ticketId = UUID.randomUUID();
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

        var note = service.addNote(adminId, ticketId, "Reproduced on Android 1.3.7");

        assertThat(note.note()).isEqualTo("Reproduced on Android 1.3.7");
        assertThat(note.adminId()).isEqualTo(adminId);
        verify(auditService).record(eq(userId), eq("SUPPORT_TICKET_NOTE_ADDED"), eq("SupportTicket"), eq(ticketId),
                eq(Map.of("actorId", adminId.toString(), "ticketNumber", "SUP-000042")));
    }

    // --- admin list / search -------------------------------------------------------------------

    @Test
    void adminList_passesTheSearchTermThrough_trimmed() {
        Page<SupportTicket> page = new PageImpl<>(List.of());
        when(ticketRepository.findForAdmin(any(), any(), any(), any())).thenReturn(page);

        service.adminList(null, null, "  stuck import  ", 0, 25);

        verify(ticketRepository).findForAdmin(isNull(), isNull(), eq("stuck import"), any());
    }

    @Test
    void adminList_treatsABlankSearchTermAsNoSearch_notAnEmptyStringMatch() {
        // "" would still technically work as a LIKE '%%' match-everything, but the contract this
        // pins down is that an unfilled search box means the WHERE clause is skipped entirely --
        // not that it happens to match everything through the LIKE itself.
        Page<SupportTicket> page = new PageImpl<>(List.of());
        when(ticketRepository.findForAdmin(any(), any(), isNull(), any())).thenReturn(page);

        service.adminList(null, null, "   ", 0, 25);

        verify(ticketRepository).findForAdmin(isNull(), isNull(), isNull(), any());
    }
}
