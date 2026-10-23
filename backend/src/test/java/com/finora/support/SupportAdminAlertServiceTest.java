package com.finora.support;

import com.finora.config.EmailProperties;
import com.finora.entity.ClientPlatform;
import com.finora.entity.FeedbackEntry;
import com.finora.entity.SupportTicket;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import com.finora.service.EmailMessage;
import com.finora.service.EmailProvider;
import com.finora.service.EmailResult;
import com.finora.service.ProviderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The email's content and the failure rules, with the recipient lookup and the provider mocked.
 * That the alert really fires after a real commit, to the real ADMIN and SUPER_ADMIN roles, is
 * {@code SupportAdminAlertIT}'s job.
 */
class SupportAdminAlertServiceTest {

    private static final Instant WHEN = Instant.parse("2026-10-23T09:05:00Z");

    private UserRepository userRepository;
    private EmailProvider emailProvider;
    private SupportAdminAlertService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        emailProvider = mock(EmailProvider.class);
        EmailProperties emailProperties = mock(EmailProperties.class);
        when(emailProperties.getAdminAppBaseUrl()).thenReturn("https://admin.example.com");
        when(emailProvider.send(any())).thenReturn(EmailResult.success(ProviderType.RESEND, "msg-1"));
        service = new SupportAdminAlertService(userRepository, emailProvider, emailProperties);
    }

    private static User admin(String email, String status) {
        User user = new User();
        user.setEmail(email);
        user.setStatus(status);
        return user;
    }

    private void holders(User... users) {
        when(userRepository.findByPermissionNameAndAccountScope("SUPPORT_MANAGE", User.SCOPE_ADMIN))
                .thenReturn(List.of(users));
    }

    private List<EmailMessage> sent(int expected) {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailProvider, times(expected)).send(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void aRaisedTicket_emailsEachActiveAdmin_withTheTicketFactsAndALinkToItsPage() {
        UUID ticketId = UUID.randomUUID();
        holders(admin("a@example.com", User.STATUS_ACTIVE), admin("b@example.com", User.STATUS_ACTIVE));

        service.alertTicketRaised(ticketId, "SUP-000042", SupportTicket.Category.TECHNICAL_ISSUE,
                ClientPlatform.MOBILE_ANDROID, WHEN);

        List<EmailMessage> messages = sent(2);
        assertThat(messages).extracting(EmailMessage::to).containsExactly("a@example.com", "b@example.com");
        EmailMessage message = messages.get(0);
        assertThat(message.subject()).isEqualTo("New support ticket — SUP-000042");
        assertThat(message.html())
                .contains("SUP-000042")
                .contains("Technical issue")
                .contains("Mobile android")
                .contains("23 Oct 2026, 09:05 UTC")
                .contains("https://admin.example.com/support-tickets/" + ticketId);
    }

    @Test
    void sentFeedback_emailsAdmins_withTypeContextAndSourceAndALinkToTheFeedbackPage() {
        holders(admin("a@example.com", User.STATUS_ACTIVE));

        service.alertFeedbackReceived(FeedbackEntry.Type.FEATURE_REQUEST, FeedbackEntry.Context.IMPORT_FLOW,
                ClientPlatform.WEB, WHEN);

        EmailMessage message = sent(1).get(0);
        assertThat(message.subject()).isEqualTo("New feedback — Feature request");
        assertThat(message.html())
                .contains("Feature request")
                .contains("Import flow")
                .contains("Web")
                .contains("https://admin.example.com/feedback");
    }

    @Test
    void suspendedAndDeactivatedAdminsAreSkipped_theBootstrapAdminIsSuspendedAfterSetup() {
        holders(admin("active@example.com", User.STATUS_ACTIVE),
                admin("suspended@example.com", User.STATUS_SUSPENDED),
                admin("deactivated@example.com", User.STATUS_DEACTIVATED));

        service.alertTicketRaised(UUID.randomUUID(), "SUP-000001", SupportTicket.Category.OTHER,
                ClientPlatform.WEB, WHEN);

        assertThat(sent(1)).extracting(EmailMessage::to).containsExactly("active@example.com");
    }

    @Test
    void noRecipients_sendsNothingAndDoesNotThrow() {
        holders();

        assertThatCode(() -> service.alertFeedbackReceived(FeedbackEntry.Type.BUG, FeedbackEntry.Context.OTHER,
                ClientPlatform.WEB, WHEN)).doesNotThrowAnyException();

        verify(emailProvider, never()).send(any());
    }

    @Test
    void oneAddressFailing_orThrowing_doesNotStopTheNextAdminGettingTheirCopy() {
        holders(admin("bounces@example.com", User.STATUS_ACTIVE),
                admin("throws@example.com", User.STATUS_ACTIVE),
                admin("fine@example.com", User.STATUS_ACTIVE));
        when(emailProvider.send(any())).thenAnswer(invocation -> {
            EmailMessage message = invocation.getArgument(0);
            if (message.to().equals("throws@example.com")) throw new IllegalStateException("provider exploded");
            if (message.to().equals("bounces@example.com")) {
                return EmailResult.failure(ProviderType.RESEND, "mailbox full");
            }
            return EmailResult.success(ProviderType.RESEND, "ok");
        });

        assertThatCode(() -> service.alertTicketRaised(UUID.randomUUID(), "SUP-000002",
                SupportTicket.Category.OTHER, ClientPlatform.WEB, WHEN)).doesNotThrowAnyException();

        assertThat(sent(3)).extracting(EmailMessage::to)
                .containsExactly("bounces@example.com", "throws@example.com", "fine@example.com");
    }

    @Test
    void theRecipientLookupFailing_isLoggedNotThrown() {
        when(userRepository.findByPermissionNameAndAccountScope(any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatCode(() -> service.alertFeedbackReceived(FeedbackEntry.Type.GENERAL, FeedbackEntry.Context.HELP,
                ClientPlatform.WEB, WHEN)).doesNotThrowAnyException();

        verify(emailProvider, never()).send(any());
    }

    @Test
    void aTicketNumberIsEscapedBeforeItGoesIntoTheHtml() {
        holders(admin("a@example.com", User.STATUS_ACTIVE));

        service.alertTicketRaised(UUID.randomUUID(), "<script>x</script>", SupportTicket.Category.OTHER,
                ClientPlatform.WEB, WHEN);

        assertThat(sent(1).get(0).html()).doesNotContain("<script>").contains("&lt;script&gt;");
    }
}
