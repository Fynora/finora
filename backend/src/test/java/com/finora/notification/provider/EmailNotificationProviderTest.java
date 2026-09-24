package com.finora.notification.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.config.EmailProperties;
import com.finora.entity.User;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import com.finora.repository.UserRepository;
import com.finora.service.EmailMessage;
import com.finora.service.EmailProvider;
import com.finora.service.EmailResult;
import com.finora.service.ProviderType;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EmailNotificationProviderTest {

    private EmailProvider emailProvider;
    private UserRepository userRepository;
    private EmailNotificationProvider provider;

    @BeforeEach
    void setUp() {
        emailProvider = mock(EmailProvider.class);
        userRepository = mock(UserRepository.class);
        EmailProperties emailProperties = new EmailProperties();
        emailProperties.setSupportFromAddress("support@example.test");
        provider = new EmailNotificationProvider(emailProvider, userRepository, emailProperties);
        when(emailProvider.isConfigured()).thenReturn(true);
    }

    // PASSWORD_CHANGED, not either statement type: the generic pre-flight guards and the
    // buildMessage() path this default backs are not type-specific, and PASSWORD_CHANGED is the
    // one NotificationType this class does not special-case (see sendFor's own doc).
    private Notification notification() {
        return notification(NotificationType.PASSWORD_CHANGED);
    }

    private Notification notification(NotificationType type) {
        return Notification.create(UUID.randomUUID(), type,
                NotificationCategory.FINANCIAL, NotificationChannel.EMAIL,
                NotificationPriority.NORMAL, "K1:EMAIL", "Statement ready",
                "We finished importing your statement.", Instant.now());
    }

    private Notification notification(NotificationType type, Map<String, String> params) {
        return Notification.create(UUID.randomUUID(), type,
                NotificationCategory.FINANCIAL, NotificationChannel.EMAIL,
                NotificationPriority.NORMAL, "K1:EMAIL", "Statement ready",
                "We finished importing your statement.", params, Instant.now());
    }

    private User activeUser() {
        User user = new User();
        user.setEmail("user@example.com");
        user.setStatus(User.STATUS_ACTIVE);
        return user;
    }

    @Test
    void channel_isEmail() {
        assertThat(provider.channel()).isEqualTo(NotificationChannel.EMAIL);
    }

    @Test
    void send_failsWhenTheUserHasNoEmailOnFile() {
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.detail()).doesNotContain("@");
        // Fix wave, IMPORTANT 4: retrying cannot make a user row that doesn't exist appear.
        assertThat(result.permanent()).isTrue();
    }

    @Test
    void send_neverThrowsWhenTheUnderlyingProviderThrows() {
        when(userRepository.findById(any())).thenThrow(new RuntimeException("db down"));

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        // A transient infrastructure exception carries no evidence this is permanently
        // undeliverable -- must stay on the ordinary retry path.
        assertThat(result.permanent()).isFalse();
    }

    @Test
    void send_failsWithoutLeakingWhenTheUserHasABlankEmail() {
        User user = new User();
        user.setEmail("");
        user.setStatus(User.STATUS_ACTIVE);
        when(userRepository.findById(any())).thenReturn(Optional.of(user));

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.detail()).doesNotContain("@");
        assertThat(result.permanent()).isTrue();
        verify(emailProvider, never()).send(any());
    }

    @Test
    void send_failsWithoutLeakingWhenTheUserHasANullEmail() {
        User user = new User();
        user.setEmail(null);
        user.setStatus(User.STATUS_ACTIVE);
        when(userRepository.findById(any())).thenReturn(Optional.of(user));

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.detail()).doesNotContain("@");
        assertThat(result.permanent()).isTrue();
        verify(emailProvider, never()).send(any());
    }

    @Test
    void send_refusesDeliveryToAPurgedUserEvenThoughItsSentinelEmailIsNonBlank() {
        User user = new User();
        // AccountPurgeSweepService.purgeOne() overwrites a purged account's email with this
        // synthetic, non-blank sentinel -- a null/blank check alone would miss it.
        user.setEmail("deleted-" + UUID.randomUUID() + "@deleted.finora.invalid");
        user.setStatus(User.STATUS_DELETED);
        when(userRepository.findById(any())).thenReturn(Optional.of(user));

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.detail()).doesNotContain("@");
        // A purge is not undone by waiting and retrying.
        assertThat(result.permanent()).isTrue();
        verify(emailProvider, never()).send(any());
    }

    /** The flip side: a real provider call that itself reports failure is not confidently
     *  permanent (could be a transient outage on the provider's side) and must stay retryable. */
    @Test
    void send_aProviderReportedFailureIsNotPermanent() {
        User user = new User();
        user.setEmail("user@example.com");
        user.setStatus(User.STATUS_ACTIVE);
        when(userRepository.findById(any())).thenReturn(Optional.of(user));
        when(emailProvider.send(any()))
                .thenReturn(EmailResult.failure(ProviderType.RESEND, "provider outage"));

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.permanent()).isFalse();
    }

    // ------------------------------------------------------------------ sender + HTML wrapping

    /**
     * The two statement types bypass {@code buildMessage()}/{@code EmailLayout} entirely and
     * delegate straight to {@link EmailProvider}'s own rich-HTML builders -- the branded wrapper,
     * CTA button, and support-sender choice are all {@code ResendEmailProvider}'s own tested
     * responsibility now (see {@code ResendEmailProviderTest.statementReady_...}/
     * {@code statementHeld_...}), not this class's. What this class still owns is recovering
     * {@code bank}/{@code jobId} from {@link Notification#getParams()} -- lost the moment
     * title/message were rendered to plain strings -- and calling the right method with them.
     */
    @Test
    void send_delegatesToTheStatementReadyBuilderWithBankAndJobIdFromParams() {
        when(userRepository.findById(any())).thenReturn(Optional.of(activeUser()));
        when(emailProvider.sendStatementReadyEmail(any(), any(), any()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "id-1"));
        Notification notification = notification(NotificationType.IMPORT_STATEMENT_READY,
                Map.of("bank", "HDFC Bank", "jobId", "11111111-1111-1111-1111-111111111111"));

        provider.send(notification);

        verify(emailProvider).sendStatementReadyEmail(
                "user@example.com", "HDFC Bank", "11111111-1111-1111-1111-111111111111");
        verify(emailProvider, never()).send(any());
    }

    /** No params needed for held -- there is nothing yet to review, only to wait for, so
     *  {@code EmailProvider.sendStatementHeldEmail} takes no job-specific argument at all. */
    @Test
    void send_delegatesToTheStatementHeldBuilder() {
        when(userRepository.findById(any())).thenReturn(Optional.of(activeUser()));
        when(emailProvider.sendStatementHeldEmail(any()))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "id-1"));

        provider.send(notification(NotificationType.IMPORT_STATEMENT_HELD));

        verify(emailProvider).sendStatementHeldEmail("user@example.com");
        verify(emailProvider, never()).send(any());
    }

    /**
     * An admin's reply about a statement is exactly the email a person will answer, so it goes out as
     * support@ like the held and ready emails, not noreply@.
     */
    @Test
    void send_usesTheSupportSenderForAnAdminsResolutionMessage() {
        when(userRepository.findById(any())).thenReturn(Optional.of(activeUser()));
        when(emailProvider.send(any())).thenReturn(EmailResult.success(ProviderType.RESEND, "id-1"));
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);

        provider.send(notification(NotificationType.IMPORT_STATEMENT_RESOLVED));

        verify(emailProvider).send(captor.capture());
        assertThat(captor.getValue().sender()).isEqualTo(EmailMessage.Sender.SUPPORT);
    }

    /**
     * The message is operator-typed free text placed in a customer's inbox. It must arrive as text:
     * markup an admin pastes (or a compromised admin session injects) is escaped, never rendered.
     */
    @Test
    void send_rendersAnAdminsMessageAsTextNotMarkup() {
        when(userRepository.findById(any())).thenReturn(Optional.of(activeUser()));
        when(emailProvider.send(any())).thenReturn(EmailResult.success(ProviderType.RESEND, "id-1"));
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        Notification hostile = Notification.create(UUID.randomUUID(), NotificationType.IMPORT_STATEMENT_RESOLVED,
                NotificationCategory.FINANCIAL, NotificationChannel.EMAIL, NotificationPriority.NORMAL,
                "K2:EMAIL", "Update on your statement",
                "Re-download it <a href=\"https://evil.example\">here</a><script>alert(1)</script>\nThanks",
                Instant.now());

        provider.send(hostile);

        verify(emailProvider).send(captor.capture());
        String html = captor.getValue().html();
        assertThat(html).doesNotContain("<script>").doesNotContain("<a href=\"https://evil.example\">");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).as("a newline the admin typed is kept as a line break").contains("<br>Thanks");
    }

    /** Every other DB-template type stays on the default (noreply@) sender -- PASSWORD_CHANGED is
     *  the one other type the enum declares today, even though nothing calls it live yet (the
     *  actual password-changed email is ResendEmailProvider's own hand-built send). */
    @Test
    void send_usesTheDefaultSenderForEveryOtherNotificationType() {
        when(userRepository.findById(any())).thenReturn(Optional.of(activeUser()));
        when(emailProvider.send(any())).thenReturn(EmailResult.success(ProviderType.RESEND, "id-1"));
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);

        provider.send(notification(NotificationType.PASSWORD_CHANGED));

        verify(emailProvider).send(captor.capture());
        assertThat(captor.getValue().sender()).isEqualTo(EmailMessage.Sender.DEFAULT);
    }

    /** Found live in testing: this used to send the notification's raw plain-text body with no
     *  styling at all -- "this one line is looking very bad". Both the branded HTML and the
     *  original plain sentence (a fallback for clients that strip HTML) must reach the message. */
    @Test
    void send_wrapsTheBodyInBrandedHtmlWhileKeepingThePlainTextFallback() {
        when(userRepository.findById(any())).thenReturn(Optional.of(activeUser()));
        when(emailProvider.send(any())).thenReturn(EmailResult.success(ProviderType.RESEND, "id-1"));
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);

        provider.send(notification());

        verify(emailProvider).send(captor.capture());
        EmailMessage sent = captor.getValue();
        assertThat(sent.html()).contains("FYNORA").contains("We finished importing your statement.");
        assertThat(sent.text()).isEqualTo("We finished importing your statement.");
    }

    /**
     * End-to-end proof for the fix found in review: the branded email footer must show whatever
     * EmailProperties.getSupportFromAddress() actually resolves to, not a hardcoded literal that
     * could silently disagree with it. Uses PASSWORD_CHANGED (a DEFAULT-sender type) so the
     * footer takes the "name the address explicitly" branch rather than the "just reply" one.
     */
    @Test
    void send_pointsTheFooterAtWhateverAddressIsActuallyConfigured() {
        when(userRepository.findById(any())).thenReturn(Optional.of(activeUser()));
        when(emailProvider.send(any())).thenReturn(EmailResult.success(ProviderType.RESEND, "id-1"));
        EmailProperties emailProperties = new EmailProperties();
        emailProperties.setSupportFromAddress("reconfigured-support@example.test");
        provider = new EmailNotificationProvider(emailProvider, userRepository, emailProperties);
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);

        provider.send(notification(NotificationType.PASSWORD_CHANGED));

        verify(emailProvider).send(captor.capture());
        assertThat(captor.getValue().html()).contains("reconfigured-support@example.test");
    }

    /** Referral emails go through the generic template path; as FINANCIAL they carry the opt-out line. */
    @Test
    void send_aFinancialTemplateEmailLinksToTheNotificationSettings() {
        when(userRepository.findById(any())).thenReturn(Optional.of(activeUser()));
        when(emailProvider.send(any())).thenReturn(EmailResult.success(ProviderType.RESEND, "id-1"));
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);

        provider.send(notification(NotificationType.REFERRAL_GRANT_ACTIVATED));

        verify(emailProvider).send(captor.capture());
        assertThat(captor.getValue().html()).contains("/app/settings?tab=notifications");
    }

    /** SECURITY cannot be switched off (the resolver forces it on), so its email must not offer to. */
    @Test
    void send_aSecurityTemplateEmailHasNoOptOutLine() {
        when(userRepository.findById(any())).thenReturn(Optional.of(activeUser()));
        when(emailProvider.send(any())).thenReturn(EmailResult.success(ProviderType.RESEND, "id-1"));
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        Notification security = Notification.create(UUID.randomUUID(), NotificationType.PASSWORD_CHANGED,
                NotificationCategory.SECURITY, NotificationChannel.EMAIL, NotificationPriority.NORMAL,
                "K3:EMAIL", "Password changed", "Your password was changed.", Instant.now());

        provider.send(security);

        verify(emailProvider).send(captor.capture());
        assertThat(captor.getValue().html()).doesNotContain("notification settings");
    }
}
