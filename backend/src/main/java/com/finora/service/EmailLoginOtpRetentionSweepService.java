package com.finora.service;

import com.finora.repository.EmailLoginOtpRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Every email_login_otps row is functionally dead within minutes -- consumed on the next
 * successful verify, or superseded by markAllUnconsumedAsConsumed the next time a code is
 * requested (AuthService.requestEmailLoginOtp).
 *
 * <p>The rows are kept for 24 hours all the same, and since audit F-07 (2026-11-21) that is a
 * need, not a margin: {@link SignInCodePause} counts the wrong tries recorded on an account's rows
 * over that window to decide whether its code sign-in is paused. The cutoff here is that class's
 * own window. Shortening it would let wrong codes be forgotten early and the pause lift early.
 * The audit trail (AuditService, via recordOtpLoginFailure/EMAIL_SENT) remains the durable record
 * of what happened; nothing reads a row older than the window.
 */
@Component
public class EmailLoginOtpRetentionSweepService {

    private static final Logger log = LoggerFactory.getLogger(EmailLoginOtpRetentionSweepService.class);

    private final EmailLoginOtpRepository emailLoginOtpRepository;

    public EmailLoginOtpRetentionSweepService(EmailLoginOtpRepository emailLoginOtpRepository) {
        this.emailLoginOtpRepository = emailLoginOtpRepository;
    }

    /** {@code @Transactional} is required, not decoration: deleteByCreatedAtBefore is a derived
     *  delete (it loads each row and calls EntityManager.remove), which throws
     *  TransactionRequiredException when the scheduler calls this with no transaction open --
     *  whenever there is at least one row to delete. See EmailLoginOtpRetentionSweepServiceIT. */
    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public void sweep() {
        Instant cutoff = Instant.now().minus(SignInCodePause.WINDOW);
        int deleted = emailLoginOtpRepository.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Deleted {} expired email login OTP rows older than {}", deleted, cutoff);
        }
    }
}
