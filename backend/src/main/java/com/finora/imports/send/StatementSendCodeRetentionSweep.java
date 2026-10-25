package com.finora.imports.send;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes send-a-statement codes (V293) a day after they were issued. Every code is dead within
 * minutes: used once, cancelled by the next, or expired after 10. Nothing reads one a day later --
 * the phone only ever shows its latest code, and the audit log (STATEMENT_SEND_CODE_ISSUED and
 * _USED) is the lasting record. The same reasoning and margin as EmailLoginOtpRetentionSweepService:
 * 24 hours is a safety margin against sweep downtime, not a retention need.
 *
 * <p>One SQL DELETE, so no transaction is needed (unlike a derived delete).
 */
@Component
public class StatementSendCodeRetentionSweep {

    private static final Logger log = LoggerFactory.getLogger(StatementSendCodeRetentionSweep.class);

    private final JdbcTemplate jdbc;

    public StatementSendCodeRetentionSweep(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 20 3 * * *")
    public void sweep() {
        int deleted = jdbc.update("DELETE FROM statement_send_codes WHERE created_at < now() - interval '24 hours'");
        if (deleted > 0) {
            log.info("Deleted {} send-a-statement codes issued more than a day ago", deleted);
        }
    }
}
