package com.finora.imports.original;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.exception.ErrorCode;
import com.finora.imports.storage.ContentAddress;
import com.finora.imports.storage.StatementStorage;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPreference;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.repository.NotificationPreferenceRepository;
import com.finora.notification.repository.NotificationRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import com.finora.service.EmailMessage;
import com.finora.service.EmailProvider;
import com.finora.service.EmailResult;
import com.finora.service.HeldItemAdminAlertService;
import com.finora.service.ProviderType;
import com.finora.service.StatementStatusNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What an opening tells people: every active Super Admin by email (who, when, which item, why --
 * nothing about the customer or the statement), and the customer, once per job per India day, the
 * date and that it was to fix a reading problem -- never who. The alert and the notifier are called
 * directly here; {@code AdminOriginalViewIT} proves the whole path through HTTP: the notice inside
 * the opening's transaction, the email after commit and off the request.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-original-view-notices-it"
})
class OriginalViewNoticesIT extends AbstractIntegrationTest {

    /** The customer's own file name. It must never reach the Super Admin email. */
    private static final String CUSTOMER_FILE_NAME = "Asha Verma statement.pdf"; // synthetic-ok: invented name

    @Autowired private HeldItemAdminAlertService alerts;
    @Autowired private StatementStatusNotifier notifier;
    @Autowired private StatementOriginalViewRepository views;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private NotificationPreferenceRepository preferences;
    @Autowired private StatementStorage storage;
    @MockitoBean private EmailProvider emailProvider;

    @BeforeEach
    void emailsSucceed() {
        when(emailProvider.send(any())).thenReturn(EmailResult.success(ProviderType.RESEND, "id-1"));
    }

    private User createUser(String role) {
        User user = new User();
        user.setEmail("original-view-notices-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Original View Notices IT User");
        user.setRole(role);
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private ImportJob heldJob() {
        User owner = createUser("USER");
        ContentAddress address = storage.store(("%PDF-1.4 notices " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        ImportJob job = new ImportJob(owner.getId(), CUSTOMER_FILE_NAME, address.hash(), address.key(), "PDF");
        job.markClaimed("worker", Instant.now());
        job.markClaimed("worker", Instant.now());
        job.recordFailure("IllegalStateException: no header row", "IllegalStateException",
                ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT, Instant.now());
        job.holdForReview("IllegalStateException", Instant.now());
        return importJobRepository.save(job);
    }

    private StatementOriginalView savedViewAt(User viewer, ImportJob job, String reason, Instant at) {
        return views.save(StatementOriginalView.open(job.getId(), job.getUserId(), viewer.getId(), "IMPORT",
                job.getId().toString(), "PDF", 2, at));
    }

    private StatementOriginalView savedView(User viewer, String reason) {
        return savedViewAt(viewer, heldJob(), reason, Instant.now());
    }

    private StatementOriginalView savedViewAt(User viewer, Instant at) {
        return savedViewAt(viewer, heldJob(), "Rows on page 2 are missing from the preview", at);
    }

    private StatementOriginalView savedViewForSameJob(StatementOriginalView first, String reason) {
        ImportJob job = importJobRepository.findById(first.getJobId()).orElseThrow();
        return savedViewAt(userRepository.findById(first.getViewerId()).orElseThrow(), job, reason,
                first.getOpenedAt().plusSeconds(600));
    }

    /** What OriginalStatementViewService does inside the opening's transaction. */
    private void tellCustomer(StatementOriginalView view) {
        notifier.notifyOpenedForFix(view.getSubjectUserId(), view.getJobId(),
                OriginalStatementViewService.noticeDay(view.getOpenedAt()));
    }

    private List<EmailMessage> sent() {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        if (mockingDetails(emailProvider).getInvocations().stream().noneMatch(i -> i.getMethod().getName().equals("send"))) {
            return List.of();
        }
        verify(emailProvider, atLeast(1)).send(captor.capture());
        return captor.getAllValues();
    }

    private List<String> sentTo() {
        return sent().stream().map(EmailMessage::to).toList();
    }

    private String lastHtmlTo(String email) {
        List<EmailMessage> to = sent().stream().filter(m -> m.to().equals(email)).toList();
        assertThat(to).as("emails to " + email).isNotEmpty();
        return to.get(to.size() - 1).html();
    }

    private List<Notification> notificationsFor(UUID userId, NotificationType type) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(n -> n.getType() == type).toList();
    }

    private Notification onlyNotificationFor(UUID userId, NotificationChannel channel) {
        List<Notification> found = notificationsFor(userId, NotificationType.IMPORT_STATEMENT_OPENED_FOR_FIX).stream()
                .filter(n -> n.getChannel() == channel).toList();
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    @Test
    void anOpeningEmailsEverySuperAdminAndNoOneElse() {
        User superAdmin = createUser("SUPER_ADMIN");
        User otherAdmin = createUser("ADMIN");
        StatementOriginalView view = savedView(otherAdmin, "Rows on page 2 are missing from the preview");
        User owner = userRepository.findById(view.getSubjectUserId()).orElseThrow();

        alerts.alertOriginalOpened(view.getId(), "Rows on page 2 are missing from the preview");

        assertThat(sentTo()).contains(superAdmin.getEmail()).doesNotContain(otherAdmin.getEmail(), owner.getEmail());
        EmailMessage email = sent().stream().filter(m -> m.to().equals(superAdmin.getEmail())).findFirst().orElseThrow();
        assertThat(email.subject()).isEqualTo("A held statement was opened — " + view.getItemRef());
        String html = lastHtmlTo(superAdmin.getEmail());
        assertThat(html).contains(otherAdmin.getEmail()).contains("Rows on page 2 are missing from the preview")
                .contains(view.getJobId().toString()).contains("/held-imports");
        assertThat(html).doesNotContain(owner.getEmail()).doesNotContain(CUSTOMER_FILE_NAME)
                .doesNotContain("Asha").doesNotContain(owner.getFullName());
    }

    @Test
    void aSuspendedSuperAdminIsNotEmailed() {
        User suspended = createUser("SUPER_ADMIN");
        suspended.setStatus("SUSPENDED");
        userRepository.save(suspended);
        StatementOriginalView view = savedView(createUser("ADMIN"), "Rows on page 2 are missing from the preview");

        alerts.alertOriginalOpened(view.getId(), "Rows on page 2 are missing from the preview");

        assertThat(sentTo()).doesNotContain(suspended.getEmail());
    }

    /** An opening of the staged rows says so, rather than talking about pages. */
    @Test
    void aStagedRowsOpeningIsNamedAsSuch() {
        User superAdmin = createUser("SUPER_ADMIN");
        User admin = createUser("ADMIN");
        ImportJob job = heldJob();
        StatementOriginalView view = views.save(StatementOriginalView.open(job.getId(), job.getUserId(), admin.getId(),
                "TRUST", "HLD-2026-930001", "ROWS", 42, Instant.now()));

        alerts.alertOriginalOpened(view.getId(), "Rows on page 2 are missing from the preview");

        EmailMessage email = sent().stream().filter(m -> m.to().equals(superAdmin.getEmail())).findFirst().orElseThrow();
        assertThat(email.subject()).isEqualTo("A held statement's rows were opened — HLD-2026-930001");
        assertThat(email.html()).contains("the staged rows").contains("42 rows").doesNotContain("Pages:");
    }

    @Test
    void theReasonIsEscapedInTheEmail() {
        User superAdmin = createUser("SUPER_ADMIN");
        StatementOriginalView view = savedView(createUser("ADMIN"), "<script>x</script> rows missing on page 2");

        alerts.alertOriginalOpened(view.getId(), "<script>x</script> rows missing on page 2");

        assertThat(lastHtmlTo(superAdmin.getEmail()))
                .doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    void aViewThatIsGoneSendsNothing() {
        createUser("SUPER_ADMIN");

        alerts.alertOriginalOpened(UUID.randomUUID(), "Rows on page 2 are missing from the preview");

        assertThat(sentTo()).isEmpty();
    }

    @Test
    void twoOpeningsOnOneDaySendOneCustomerNotice() {
        // A fixed midday instant, not Instant.now(): the second opening is ten minutes after the
        // first, so a run in the last ten minutes of an India day put the two openings on
        // different days -- two dedupe keys, two notices -- and failed this test on main's CI.
        StatementOriginalView first = savedViewAt(createUser("ADMIN"), Instant.parse("2026-10-14T06:30:00Z"));   // 14 Oct, 12:00 IST
        StatementOriginalView second = savedViewForSameJob(first, "Second look at the rows on page 2");   // 12:10 IST

        tellCustomer(first);
        tellCustomer(second);

        // One notice: one row per channel, each written once under the day's dedupe key.
        List<Notification> sentToCustomer = notificationsFor(first.getSubjectUserId(),
                NotificationType.IMPORT_STATEMENT_OPENED_FOR_FIX);
        assertThat(sentToCustomer).extracting(Notification::getChannel)
                .containsExactlyInAnyOrder(NotificationChannel.PUSH, NotificationChannel.EMAIL);
    }

    @Test
    void anOpeningOnTheNextIndiaDayIsANewNotice() {
        User admin = createUser("ADMIN");
        StatementOriginalView first = savedViewAt(admin, Instant.parse("2026-10-14T17:00:00Z"));   // 14 Oct, 22:30 IST
        ImportJob job = importJobRepository.findById(first.getJobId()).orElseThrow();
        StatementOriginalView second = savedViewAt(admin, job, "Second look at the rows on page 2",
                Instant.parse("2026-10-14T19:00:00Z"));   // 15 Oct, 00:30 IST

        tellCustomer(first);
        tellCustomer(second);

        assertThat(notificationsFor(first.getSubjectUserId(), NotificationType.IMPORT_STATEMENT_OPENED_FOR_FIX)
                .stream().filter(n -> n.getChannel() == NotificationChannel.PUSH))
                .extracting(Notification::getMessage)
                .containsExactlyInAnyOrder("On 14 Oct 2026 our team opened it to fix a reading problem.",
                        "On 15 Oct 2026 our team opened it to fix a reading problem.");
    }

    /** Sid, 2026-10-15: the notice is SECURITY, always sent. A customer who turned off financial
     *  emails and pushes still gets both. */
    @Test
    void theNoticeReachesACustomerWhoTurnedOffFinancialNotifications() {
        StatementOriginalView view = savedView(createUser("ADMIN"), "Rows on page 2 are missing from the preview");
        for (NotificationChannel channel : List.of(NotificationChannel.EMAIL, NotificationChannel.PUSH)) {
            preferences.save(NotificationPreference.of(view.getSubjectUserId(), NotificationCategory.FINANCIAL,
                    channel, false));
        }

        tellCustomer(view);

        assertThat(notificationsFor(view.getSubjectUserId(), NotificationType.IMPORT_STATEMENT_OPENED_FOR_FIX))
                .extracting(Notification::getChannel, Notification::getCategory)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(NotificationChannel.PUSH, NotificationCategory.SECURITY),
                        org.assertj.core.groups.Tuple.tuple(NotificationChannel.EMAIL, NotificationCategory.SECURITY));
    }

    @Test
    void theCustomerNoticeNamesTheDateInIndiaTimeAndNoOne() {
        // A view opened at 2026-10-14T20:00:00Z is 15 Oct 2026 in India.
        User admin = createUser("ADMIN");
        StatementOriginalView view = savedViewAt(admin, Instant.parse("2026-10-14T20:00:00Z"));

        tellCustomer(view);

        Notification pushed = onlyNotificationFor(view.getSubjectUserId(), NotificationChannel.PUSH);
        assertThat(pushed.getTitle()).isEqualTo("Your statement was opened");
        assertThat(pushed.getMessage()).isEqualTo("On 15 Oct 2026 our team opened it to fix a reading problem.");
        Notification emailed = onlyNotificationFor(view.getSubjectUserId(), NotificationChannel.EMAIL);
        assertThat(emailed.getTitle()).isEqualTo("We opened your statement to fix a reading problem");
        assertThat(emailed.getMessage()).isEqualTo("On 15 Oct 2026, a member of our team opened the statement you "
                + "uploaded so we could fix a problem reading it. Only administrators can do this, and every opening "
                + "is recorded. Nothing in your accounts was changed by it.");
        for (Notification n : List.of(pushed, emailed)) {
            assertThat(n.getTitle() + " " + n.getMessage()).doesNotContain(admin.getEmail())
                    .doesNotContain(admin.getFullName()).doesNotContain("Rows on page 2");
        }
    }
}
