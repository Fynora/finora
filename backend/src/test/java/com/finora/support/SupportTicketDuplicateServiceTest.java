package com.finora.support;

import com.finora.entity.SupportTicket;
import com.finora.exception.ApiException;
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationType;
import com.finora.repository.SupportTicketRepository;
import com.finora.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SupportTicketDuplicateServiceTest {

    private SupportTicketRepository tickets;
    private AuditService auditService;
    private NotificationService notificationService;
    private SupportTicketDuplicateService service;

    private final UUID adminId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private SupportTicket ticket;
    private SupportTicket reference;

    @BeforeEach
    void setUp() {
        tickets = mock(SupportTicketRepository.class);
        auditService = mock(AuditService.class);
        notificationService = mock(NotificationService.class);
        service = new SupportTicketDuplicateService(tickets, auditService, notificationService);

        ticket = ticket("SUP-000043", customerId, SupportTicket.Status.OPEN);
        reference = ticket("SUP-000042", customerId, SupportTicket.Status.IN_PROGRESS);
        when(tickets.existsById(ticket.getId())).thenReturn(true);
        when(tickets.findByIdForUpdate(ticket.getId())).thenReturn(Optional.of(ticket));
        stubReference(reference);
        when(tickets.save(any(SupportTicket.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** What a lookup by SUP-000042 now returns: its id, and the locked row behind it. */
    private void stubReference(SupportTicket ref) {
        reference = ref;
        when(tickets.findIdByTicketNumber("SUP-000042")).thenReturn(Optional.of(ref.getId()));
        when(tickets.findByIdForUpdate(ref.getId())).thenReturn(Optional.of(ref));
    }

    private static SupportTicket ticket(String number, UUID owner, SupportTicket.Status status) {
        SupportTicket t = new SupportTicket();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setTicketNumber(number);
        t.setUserId(owner);
        t.setStatus(status);
        t.setSubject("The customer's own subject");
        t.setDescription("The customer's own description");
        return t;
    }

    private void assertNothingHappened() {
        verify(tickets, never()).save(any());
        verifyNoInteractions(auditService, notificationService);
    }

    @Test
    void closesTheTicketAsADuplicate_recordsWhichOne_audits_andQueuesOneEmailToTheCustomer() {
        SupportTicketDto.Summary result = service.markDuplicate(adminId, ticket.getId(), "SUP-000042");

        assertThat(result.status()).isEqualTo(SupportTicket.Status.CLOSED);
        assertThat(result.duplicateOfTicketId()).isEqualTo(reference.getId());
        assertThat(result.duplicateOfTicketNumber()).isEqualTo("SUP-000042");
        assertThat(ticket.getClosedAt()).isNotNull();
        // The reference is untouched: still the live ticket the customer is told to follow.
        assertThat(reference.getStatus()).isEqualTo(SupportTicket.Status.IN_PROGRESS);
        assertThat(reference.getDuplicateOfTicketId()).isNull();

        verify(auditService).record(eq(customerId), eq("SUPPORT_TICKET_MARKED_DUPLICATE"), eq("SupportTicket"),
                eq(ticket.getId()), eq(Map.of("actorId", adminId.toString(), "ticketNumber", "SUP-000043",
                        "duplicateOfTicketNumber", "SUP-000042", "previousStatus", "OPEN")));

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).request(captor.capture());
        NotificationRequest request = captor.getValue();
        assertThat(request.userId()).isEqualTo(customerId);
        assertThat(request.type()).isEqualTo(NotificationType.SUPPORT_TICKET_DUPLICATE);
        assertThat(request.channels()).isEqualTo(Set.of(NotificationChannel.EMAIL));
        // Never suppressible by a notification preference: it answers something the customer asked.
        assertThat(request.category()).isEqualTo(NotificationCategory.SECURITY);
        // Keyed on the ticket, so it can only ever be sent once.
        assertThat(request.notificationKey()).isEqualTo("SUPPORT_TICKET_DUPLICATE_" + ticket.getId());
        // Two ticket numbers and nothing the customer wrote.
        assertThat(request.params()).isEqualTo(Map.of("ticketNumber", "SUP-000043", "referenceNumber", "SUP-000042"));
    }

    @Test
    void aTicketInProgressOrResolvedCanBeMarked_anyOpenStateCloses() {
        for (SupportTicket.Status status : new SupportTicket.Status[] {
                SupportTicket.Status.IN_PROGRESS, SupportTicket.Status.RESOLVED}) {
            SupportTicket t = ticket("SUP-0000" + status.ordinal() + "9", customerId, status);
            when(tickets.existsById(t.getId())).thenReturn(true);
            when(tickets.findByIdForUpdate(t.getId())).thenReturn(Optional.of(t));

            SupportTicketDto.Summary result = service.markDuplicate(adminId, t.getId(), "SUP-000042");

            assertThat(result.status()).as("from %s", status).isEqualTo(SupportTicket.Status.CLOSED);
        }
    }

    @Test
    void theReferenceMayBeResolvedOrClosed_thatIsStillTheTicketTheCustomerAlreadyRaised() {
        reference.setStatus(SupportTicket.Status.CLOSED);

        assertThat(service.markDuplicate(adminId, ticket.getId(), "SUP-000042").status())
                .isEqualTo(SupportTicket.Status.CLOSED);
    }

    @Test
    void theReferenceNumberIsReadForgivingly_caseAndSurroundingSpaces() {
        service.markDuplicate(adminId, ticket.getId(), "  sup-000042 ");

        verify(tickets).findIdByTicketNumber("SUP-000042");
        assertThat(ticket.getDuplicateOfTicketNumber()).isEqualTo("SUP-000042");
    }

    @Test
    void bothRowsAreLockedLowestIdFirst_beforeAnyStateIsRead_soTwoAdminsCanNeverWaitOnEachOther() {
        // Whichever of the two ids is lower must be locked first, whichever one is the ticket.
        for (int round = 0; round < 20; round++) {
            setUp();
            service.markDuplicate(adminId, ticket.getId(), "SUP-000042");

            UUID low = ticket.getId().compareTo(reference.getId()) < 0 ? ticket.getId() : reference.getId();
            UUID high = low.equals(ticket.getId()) ? reference.getId() : ticket.getId();
            org.mockito.InOrder order = org.mockito.Mockito.inOrder(tickets);
            order.verify(tickets).findByIdForUpdate(low);
            order.verify(tickets).findByIdForUpdate(high);
        }
    }

    @Test
    void aBlankReference_isA400() {
        assertThatThrownBy(() -> service.markDuplicate(adminId, ticket.getId(), "   "))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertNothingHappened();
    }

    @Test
    void anUnknownTicketOrAnUnknownReference_isA404_namingWhatWasNotFound() {
        // existsById is false for an id nobody has.
        assertThatThrownBy(() -> service.markDuplicate(adminId, UUID.randomUUID(), "SUP-000042"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> service.markDuplicate(adminId, ticket.getId(), "SUP-999999"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getMessage()).contains("SUP-999999");
                });
        assertNothingHappened();
    }

    @Test
    void aTicketCannotBeADuplicateOfItself() {
        when(tickets.findIdByTicketNumber("SUP-000043")).thenReturn(Optional.of(ticket.getId()));

        assertThatThrownBy(() -> service.markDuplicate(adminId, ticket.getId(), "SUP-000043"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("itself");
                });
        assertNothingHappened();
    }

    @Test
    void theReferenceMustBelongToTheSameCustomer_otherwiseNothingChangesAndNobodyIsEmailed() {
        stubReference(ticket("SUP-000042", UUID.randomUUID(), SupportTicket.Status.OPEN));

        assertThatThrownBy(() -> service.markDuplicate(adminId, ticket.getId(), "SUP-000042"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("different customer");
                });
        assertThat(ticket.getStatus()).isEqualTo(SupportTicket.Status.OPEN);
        assertNothingHappened();
    }

    @Test
    void aReferenceThatIsItselfADuplicate_isRefused_andTheAdminIsToldWhichTicketToUse() {
        reference.markDuplicateOf(ticket("SUP-000010", customerId, SupportTicket.Status.OPEN));
        reference.setStatus(SupportTicket.Status.CLOSED);
        stubReference(reference);

        assertThatThrownBy(() -> service.markDuplicate(adminId, ticket.getId(), "SUP-000042"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("SUP-000010").contains("instead");
                });
        assertNothingHappened();
    }

    @Test
    void anAlreadyClosedTicketCannotBeMarked_soItIsNeverEmailedTwice() {
        ticket.setStatus(SupportTicket.Status.CLOSED);

        assertThatThrownBy(() -> service.markDuplicate(adminId, ticket.getId(), "SUP-000042"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("already closed");
                });
        assertNothingHappened();
    }
}
