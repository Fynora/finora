package com.finora.service;

import com.finora.repository.EmailLoginOtpRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Every email_login_otps row is functionally dead within minutes -- consumed on the next
 * successful verify, or superseded by markAllUnconsumedAsConsumed the next time a code is
 * requested (AuthService.requestEmailLoginOtp). A 24-hour cutoff (rather than this table's own
 * 5-minute TTL) is purely a safety margin against sweep downtime, not a retention need: nothing
 * reads a row this old for any purpose -- the audit trail (AuditService, via
 * recordOtpLoginFailure/EMAIL_SENT) is the durable record of what happened here, not this table.
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
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        int deleted = emailLoginOtpRepository.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Deleted {} expired email login OTP rows older than {}", deleted, cutoff);
        }
    }
}
