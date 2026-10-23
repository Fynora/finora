package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.config.EmailProperties;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.template.RenderedMessage;
import com.finora.notification.template.TemplateRenderer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.util.HtmlUtils;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ticket acknowledgement is wording in two places: the {@code notification_templates} row (V287),
 * which becomes the notification record the admin portal shows, and the branded email
 * {@link ResendEmailProvider#buildSupportTicketReceivedMessage} sends. Nothing connects them, so a
 * word changed in one would leave the record describing an email nobody received. This renders both,
 * through the real renderer against the migrated database, and requires every sentence of the record
 * to be in the email, and the subject to be the title.
 */
class SupportTicketReceivedCopyIT extends AbstractIntegrationTest {

    private static final String TICKET_NUMBER = "SUP-000123";

    @Autowired private TemplateRenderer renderer;
    @Autowired private EmailProperties emailProperties;

    @Test
    void theRecordedMessageIsWhatTheEmailSays() {
        RenderedMessage record = renderer.render(NotificationType.SUPPORT_TICKET_RECEIVED,
                NotificationChannel.EMAIL, Map.of("ticketNumber", TICKET_NUMBER));
        EmailMessage email = new ResendEmailProvider(emailProperties).buildSupportTicketReceivedMessage(
                "customer@example.com", TICKET_NUMBER, "11111111-1111-1111-1111-111111111111");

        assertThat(email.subject()).isEqualTo(record.title());
        String emailText = visibleText(email.html());
        String[] sentences = record.body().split("(?<=[.?!])\\s+");
        assertThat(sentences).hasSizeGreaterThan(1);
        for (String sentence : sentences) {
            assertThat(emailText).as("the record says \"%s\" but the email does not", sentence)
                    .contains(normalise(sentence));
        }
    }

    /** Tags dropped, entities decoded (the footer's apostrophe is {@code &#39;} or {@code '}),
     *  whitespace collapsed -- what a reader sees, in the form the record is written in. Block
     *  boundaries become spaces so paragraphs don't run together; inline tags (the bold reference)
     *  vanish without leaving one, as they do on screen. */
    private static String visibleText(String html) {
        String withBlockBreaks = html.replaceAll("</(?:p|h1|td|tr|table)>|<br\\s*/?>", " ");
        return normalise(HtmlUtils.htmlUnescape(withBlockBreaks.replaceAll("<[^>]*>", "")));
    }

    private static String normalise(String text) {
        return Arrays.stream(text.replace('’', '\'').trim().split("\\s+")).reduce((a, b) -> a + " " + b)
                .orElse("");
    }
}
