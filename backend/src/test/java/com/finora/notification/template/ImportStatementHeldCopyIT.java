package com.finora.notification.template;

import com.finora.AbstractIntegrationTest;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** V263's held-statement copy, rendered through the real renderer against the migrated database
 *  with exactly the params StatementStatusNotifier sends -- so the 48-hour promise the held screen
 *  makes is the one the user's email and push make too, and a stale "additional checks" line in a
 *  follow-up email fails here rather than in someone's inbox. */
class ImportStatementHeldCopyIT extends AbstractIntegrationTest {

    private static final NotificationChannel[] BOTH = {NotificationChannel.EMAIL, NotificationChannel.PUSH};

    @Autowired private TemplateRenderer renderer;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void heldSaysByHandAndFortyEightHoursOnBothChannels() {
        for (NotificationChannel channel : BOTH) {
            RenderedMessage m = renderer.render(NotificationType.IMPORT_STATEMENT_HELD, channel, Map.of());
            String all = m.title() + " " + m.body();
            assertThat(all).as(channel.name()).contains("by hand").contains("48 hours")
                    .doesNotContain("additional checks").doesNotContain("{{");
            // Never suggest the statement itself is in doubt -- see importJob.ts's detail().
            assertThat(all.toLowerCase()).as(channel.name())
                    .doesNotContain("genuine", "authentic", "verify", "suspicious", "fraud");
        }
    }

    @Test
    void readyAndRejectedEmailsFollowUpInTheSameWords() {
        RenderedMessage ready = renderer.render(NotificationType.IMPORT_STATEMENT_READY, NotificationChannel.EMAIL,
                Map.of("bank", "HDFC Bank", "jobId", "11111111-1111-1111-1111-111111111111"));
        assertThat(ready.body()).contains("finished double-checking your HDFC Bank statement")
                .doesNotContain("additional checks");

        RenderedMessage rejected = renderer.render(NotificationType.IMPORT_STATEMENT_REJECTED,
                NotificationChannel.EMAIL, Map.of("jobId", "11111111-1111-1111-1111-111111111111"));
        assertThat(rejected.body()).contains("finished double-checking your statement")
                .contains("Nothing was added to your accounts").doesNotContain("additional checks");
    }

    @Test
    void theOldCopyIsRetiredNotDeletedAndEachPairHasOneActiveRow() {
        Integer retired = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM notification_templates
                WHERE active = false
                  AND (type = 'IMPORT_STATEMENT_HELD'
                       OR (type IN ('IMPORT_STATEMENT_READY', 'IMPORT_STATEMENT_REJECTED') AND channel = 'EMAIL'))
                """, Integer.class);
        assertThat(retired).isEqualTo(4);

        Integer active = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM notification_templates
                WHERE active = true
                  AND type IN ('IMPORT_STATEMENT_HELD', 'IMPORT_STATEMENT_READY', 'IMPORT_STATEMENT_REJECTED')
                """, Integer.class);
        // Held, ready and rejected each have one EMAIL and one PUSH row live.
        assertThat(active).isEqualTo(6);
    }
}
