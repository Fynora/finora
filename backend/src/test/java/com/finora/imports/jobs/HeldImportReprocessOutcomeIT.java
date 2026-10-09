package com.finora.imports.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.exception.ErrorCode;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.repository.NotificationRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.StatementStatusNotifier;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a user who was told "we'll notify you" actually hears when an admin reprocesses their held
 * import, for each way the reprocess can end -- through the real upload endpoint, the real admin
 * reprocess endpoint, the real worker and the real notification outbox.
 *
 * <p>The hold itself is put in place with the same two calls {@code ImportJobWorker.recordFailure}
 * makes for an unclassified dead-letter ({@code holdForReview}, then {@code notifyHeld}), rather
 * than produced by the worker: what this is about is a document the parser of the day held and a
 * later parser refuses under a curated code -- a payment app history that pre-dates the refusal is
 * the real case -- and one set of bytes cannot do both in one build.
 *
 * <p>Storage is on and the poller is off, as in {@code ImportJobWorkerStageIT}, so each test drives
 * {@code drainOnce()} itself.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-held-reprocess-outcome-it",
        "app.import.queue.enabled=false"
})
class HeldImportReprocessOutcomeIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private ImportJobRepository jobRepository;
    @Autowired private ImportJobWorker worker;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private StatementStatusNotifier statementStatusNotifier;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Synthetic: two rows of made-up merchants, the same shape ImportJobWorkerStageIT uses. */
    private static final byte[] GOOD_CSV = """
            Date,Description,Amount,Type
            2026-07-10,SWIGGY ORDER,486.00,DEBIT
            2026-07-11,BLINKIT GROCERIES,1240.50,DEBIT
            """.getBytes(StandardCharsets.UTF_8);

    /** Starts like a PDF and is not one: fails FAIL_FAST as IMPORT_CORRUPT_PDF, which is never held. */
    private static final byte[] DAMAGED_PDF =
            "%PDF-1.4\nthis is not a real document\n".getBytes(StandardCharsets.UTF_8);

    private User createUser(String role) {
        User user = new User();
        user.setEmail("held-reprocess-outcome-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Held Reprocess Outcome IT User");
        user.setPhoneVerified(true);
        if (role != null) {
            user.setRole(role);
            user.setAccountScope(User.SCOPE_ADMIN);
        }
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    private UUID upload(User user, byte[] content, String fileName) throws Exception {
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override public String getFilename() { return fileName; }
        });
        ResponseEntity<String> accepted = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        return UUID.fromString(mapper.readTree(accepted.getBody()).get("data").get("jobId").asText());
    }

    /** The state, and the notification, ImportJobWorker.recordFailure leaves an unclassified
     *  dead-letter in: two attempts spent, held for triage, and the user told "we're checking". */
    private void holdAsTheWorkerWould(UUID jobId) {
        transactionTemplate.executeWithoutResult(status -> {
            ImportJob job = jobRepository.findById(jobId).orElseThrow();
            job.markClaimed("worker", Instant.now());
            job.markClaimed("worker", Instant.now());
            job.recordFailure("IllegalStateException: no header row found", "IllegalStateException",
                    ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT, Instant.now());
            job.holdForReview("IllegalStateException", Instant.now());
            jobRepository.save(job);
            statementStatusNotifier.notifyHeld(job);
        });
    }

    private void reprocessAsAdmin(UUID jobId) {
        User admin = createUser("ADMIN");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/held-imports/" + jobId + "/reprocess",
                HttpMethod.POST, new HttpEntity<>(bearerFor(admin)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private List<Notification> notificationsOf(User user, NotificationType type) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .filter(n -> n.getType() == type)
                .toList();
    }

    @Test
    void aHeldImportReprocessedIntoACuratedFailureTellsTheUserOnceWithThatCodesCopy() throws Exception {
        User user = createUser(null);
        UUID jobId = upload(user, DAMAGED_PDF, "statement.pdf");
        holdAsTheWorkerWould(jobId);

        reprocessAsAdmin(jobId);
        worker.drainOnce();

        ImportJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.FAILED);
        assertThat(job.getFailureCode()).isEqualTo(ErrorCode.IMPORT_CORRUPT_PDF.name());
        assertThat(job.wasHeldForReview()).isTrue();

        List<Notification> closing = notificationsOf(user, NotificationType.IMPORT_STATEMENT_RESOLVED);
        assertThat(closing).extracting(Notification::getChannel)
                .as("one push and one email, the same pair every other statement-status message is")
                .containsExactlyInAnyOrder(NotificationChannel.PUSH, NotificationChannel.EMAIL);
        assertThat(closing).allSatisfy(n -> {
            assertThat(n.getNotificationKey()).startsWith("IMPORT_FAILED_" + jobId + ":");
            assertThat(n.getMessage()).isEqualTo("We've finished checking the statement you uploaded, "
                    + "but we couldn't import it. " + ErrorCode.IMPORT_CORRUPT_PDF.defaultMessage()
                    + ". Nothing was added to your accounts.");
            assertThat(n.getTitle()).as("V216's resolve template, rendered by the real outbox")
                    .isEqualTo(n.getChannel() == NotificationChannel.PUSH
                            ? "Update on your statement" : "An update on your statement");
        });
        assertThat(notificationsOf(user, NotificationType.IMPORT_STATEMENT_READY)).isEmpty();
        assertThat(notificationsOf(user, NotificationType.IMPORT_STATEMENT_HELD))
                .as("the hold's own message, from before the reprocess, and no second one")
                .hasSize(2);

        // A redelivery of the same closing message lands on the same outbox keys.
        transactionTemplate.executeWithoutResult(status -> statementStatusNotifier.notifyFailedAfterHold(
                jobRepository.findById(jobId).orElseThrow()));
        assertThat(notificationsOf(user, NotificationType.IMPORT_STATEMENT_RESOLVED)).hasSize(2);
    }

    @Test
    void aFirstAttemptFailureWithNoHoldBeforeItSendsNothing() throws Exception {
        User user = createUser(null);
        UUID jobId = upload(user, DAMAGED_PDF, "statement.pdf");

        worker.drainOnce();

        ImportJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.FAILED);
        assertThat(job.getFailureCode()).isEqualTo(ErrorCode.IMPORT_CORRUPT_PDF.name());
        assertThat(job.wasHeldForReview()).isFalse();
        assertThat(notificationRepository.findByUserIdOrderByCreatedAtDesc(user.getId()))
                .as("we never asked this user to wait, and the upload screen shows the failure itself")
                .isEmpty();
    }

    @Test
    void aHeldImportReprocessedIntoSuccessIsAnnouncedAsReadyAndNotAsAFailure() throws Exception {
        User user = createUser(null);
        UUID jobId = upload(user, GOOD_CSV, "statement.csv");
        holdAsTheWorkerWould(jobId);

        reprocessAsAdmin(jobId);
        worker.drainOnce();

        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(ImportJob.Status.COMPLETED);
        assertThat(notificationRepository.findByNotificationKey("IMPORT_READY_" + jobId + ":PUSH")).isPresent();
        assertThat(notificationRepository.findByNotificationKey("IMPORT_READY_" + jobId + ":EMAIL")).isPresent();
        assertThat(notificationsOf(user, NotificationType.IMPORT_STATEMENT_RESOLVED)).isEmpty();
    }
}
