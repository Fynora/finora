package com.finora.observability.jobs;

import com.finora.AbstractIntegrationTest;
import com.finora.config.EmailProperties;
import com.finora.entity.User;
import com.finora.repository.RoleRepository;
import com.finora.repository.UserRepository;
import com.finora.service.EmailMessage;
import com.finora.service.EmailProvider;
import com.finora.service.EmailResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The checker against a real database, with the email provider replaced. Uses a daily-cron job's
 * key so the real job cannot overwrite the planted row mid-test; the scheduled checker itself is held
 * off by a long initial delay, and these tests call {@code check()} directly.
 */
@TestPropertySource(properties = {
        "app.background-job-alerts.enabled=true",
        "app.background-job-alerts.initial-delay-ms=3600000"})
class BackgroundJobAlertCheckerIT extends AbstractIntegrationTest {

    private static final String DAILY_JOB = "SharedCorpusRevalidationSweepService.sweep";
    private static final String JOB_NAME = "Shared category recheck";

    @MockitoBean private EmailProvider emailProvider;
    @Autowired private BackgroundJobAlertChecker checker;
    @Autowired private JobScheduleRegistry registry;
    @Autowired private BackgroundJobStore store;
    @Autowired private EmailProperties emailProperties;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private JdbcTemplate jdbc;

    private String adminEmail;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM background_job_failures WHERE job_key = ?", DAILY_JOB);
        jdbc.update("DELETE FROM background_job_status WHERE job_key = ?", DAILY_JOB);
        when(emailProvider.send(any())).thenReturn(EmailResult.success(com.finora.service.ProviderType.RESEND, "test"));
        clearInvocations(emailProvider);
        User admin = new User();
        adminEmail = "jobs-alert-it-" + UUID.randomUUID() + "@example.com";
        admin.setEmail(adminEmail);
        admin.setPasswordHash("irrelevant-for-this-test");
        admin.setFullName("Jobs Alert IT Admin");
        admin.setRole("ADMIN");
        admin.setAccountScope(User.SCOPE_ADMIN);
        admin.setPhoneVerified(true);
        admin.getRoles().add(roleRepository.findByName("ADMIN").orElseThrow());
        userRepository.save(admin);
    }

    private void crashedRow(Instant at) {
        jdbc.update("""
                INSERT INTO background_job_status (job_key, last_started_at, last_finished_at, last_outcome,
                    consecutive_failures, broken_since, last_instance)
                VALUES (?, ?, ?, 'CRASHED', 1, ?, 'test')
                """, DAILY_JOB, utc(at), utc(at), utc(at));
        jdbc.update("""
                INSERT INTO background_job_failures (job_key, occurred_at, outcome, failed_items, error_summary)
                VALUES (?, ?, 'CRASHED', 0, 'IllegalStateException at SharedCorpusRevalidationSweepService:88')
                """, DAILY_JOB, utc(at));
    }

    private static OffsetDateTime utc(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }

    private List<EmailMessage> emailsAboutTheJob() {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailProvider, atLeast(0)).send(captor.capture());
        return captor.getAllValues().stream().filter(m -> m.subject().contains(JOB_NAME)).toList();
    }

    @Test
    void brokenJob_emailsEachDiagnosticsAdminOnce() {
        crashedRow(Instant.now().minusSeconds(120));

        checker.check();
        checker.check();

        List<EmailMessage> mine = emailsAboutTheJob().stream().filter(m -> m.to().equals(adminEmail)).toList();
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).subject()).isEqualTo("Background job needs attention: " + JOB_NAME);
        assertThat(jdbc.queryForObject("SELECT last_email_kind FROM background_job_status WHERE job_key = ?",
                String.class, DAILY_JOB)).isEqualTo("BROKEN");
    }

    @Test
    void twoCheckersAtOnce_sendOneEmail() throws Exception {
        crashedRow(Instant.now().minusSeconds(120));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                go.await();
                checker.check();
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(emailsAboutTheJob().stream().filter(m -> m.to().equals(adminEmail))).hasSize(1);
    }

    @Test
    void recoveryAfterABrokenEmail_sendsWorkingAgain() {
        crashedRow(Instant.now().minusSeconds(120));
        checker.check();
        store.finished(DAILY_JOB, "test", Instant.now(), JobOutcome.OK, 0, 5, null);

        checker.check();

        List<EmailMessage> mine = emailsAboutTheJob().stream().filter(m -> m.to().equals(adminEmail)).toList();
        assertThat(mine).extracting(EmailMessage::subject).containsExactly(
                "Background job needs attention: " + JOB_NAME,
                "Background job working again: " + JOB_NAME);
    }

    @Test
    void flagOff_sendsNothingButStillPrunes() {
        crashedRow(Instant.now().minus(Duration.ofDays(31)));
        BackgroundJobAlertChecker off = checker.withAlertsEnabled(false);

        off.check();

        assertThat(emailsAboutTheJob()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM background_job_failures WHERE job_key = ?",
                Integer.class, DAILY_JOB)).isZero();
    }

    @Test
    void prunesFailuresOlderThan30Days_keepsNewerOnes() {
        crashedRow(Instant.now().minus(Duration.ofDays(31)));
        jdbc.update("""
                INSERT INTO background_job_failures (job_key, occurred_at, outcome, failed_items, error_summary)
                VALUES (?, ?, 'CRASHED', 0, 'X at Y:1')
                """, DAILY_JOB, utc(Instant.now().minus(Duration.ofDays(29))));

        checker.check();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM background_job_failures WHERE job_key = ?",
                Integer.class, DAILY_JOB)).isEqualTo(1);
    }

    @Test
    void emailBody_namesTheJob_whatHappened_sinceWhenInIst_andLinksToThePage() {
        crashedRow(Instant.parse("2026-11-13T08:35:00Z"));

        checker.check();

        EmailMessage email = emailsAboutTheJob().stream().filter(m -> m.to().equals(adminEmail)).findFirst()
                .orElseThrow();
        String body = email.html();
        assertThat(body).contains(JOB_NAME)
                .contains("Re-decides shared categories")
                .contains("crashed")
                .contains("IllegalStateException at SharedCorpusRevalidationSweepService:88")
                .contains("13 Nov 2026, 14:05 IST")
                .contains(emailProperties.getAdminAppBaseUrl() + "/background-jobs?job=" + DAILY_JOB);
    }
}
