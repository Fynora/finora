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
 * The resolved/closed emails, like the acknowledgement (SupportTicketReceivedCopyIT), are wording in
 * two places: the V311 template rows, which become the notification record the admin portal shows,
 * and the branded email {@link ResendEmailProvider#buildSupportTicketFinishedMessage} sends. Every
 * sentence of the record must be in the email, and the subject must be the title, for both outcomes.
 * Plus the one thing only the email has: the admin's message, escaped, with its line breaks kept.
 */
class SupportTicketFinishedCopyIT extends AbstractIntegrationTest {

    private static final String TICKET_NUMBER = "SUP-000123";
    private static final String TICKET_ID = "11111111-1111-1111-1111-111111111111";

    @Autowired private TemplateRenderer renderer;
    @Autowired private EmailProperties emailProperties;

    @Test
    void theRecordedMessageIsWhatTheEmailSays_forResolved() {
        assertRecordMatchesEmail(NotificationType.SUPPORT_TICKET_RESOLVED, true);
    }

    @Test
    void theRecordedMessageIsWhatTheEmailSays_forClosed() {
        assertRecordMatchesEmail(NotificationType.SUPPORT_TICKET_CLOSED, false);
    }

    @Test
    void theAdminsMessageIsEscaped_andKeepsItsLineBreaks() {
        EmailMessage email = new ResendEmailProvider(emailProperties).buildSupportTicketFinishedMessage(
                "customer@example.com", TICKET_NUMBER, TICKET_ID, true,
                "We re-read your statement.\n<b>It is fixed</b> & imported.");

        assertThat(email.html()).contains("We re-read your statement.<br>&lt;b&gt;It is fixed&lt;/b&gt; &amp; imported.");
        assertThat(email.html()).doesNotContain("<b>It is fixed</b>");
        assertThat(visibleText(email.html())).contains("A note from our team:");
    }

    @Test
    void noMessage_noNoteSection() {
        EmailMessage email = new ResendEmailProvider(emailProperties).buildSupportTicketFinishedMessage(
                "customer@example.com", TICKET_NUMBER, TICKET_ID, false, "   ");

        assertThat(visibleText(email.html())).doesNotContain("A note from our team");
    }

    private void assertRecordMatchesEmail(NotificationType type, boolean resolved) {
        RenderedMessage record = renderer.render(type, NotificationChannel.EMAIL, Map.of("ticketNumber", TICKET_NUMBER));
        EmailMessage email = new ResendEmailProvider(emailProperties).buildSupportTicketFinishedMessage(
                "customer@example.com", TICKET_NUMBER, TICKET_ID, resolved, null);

        assertThat(email.subject()).isEqualTo(record.title());
        String emailText = visibleText(email.html());
        String[] sentences = record.body().split("(?<=[.?!])\\s+");
        assertThat(sentences).hasSizeGreaterThan(1);
        for (String sentence : sentences) {
            assertThat(emailText).as("the record says \"%s\" but the email does not", sentence)
                    .contains(normalise(sentence));
        }
    }

    /** Same reading as SupportTicketReceivedCopyIT: tags dropped, entities decoded, whitespace collapsed. */
    private static String visibleText(String html) {
        String withBlockBreaks = html.replaceAll("</(?:p|h1|td|tr|table|blockquote)>|<br\\s*/?>", " ");
        return normalise(HtmlUtils.htmlUnescape(withBlockBreaks.replaceAll("<[^>]*>", "")));
    }

    private static String normalise(String text) {
        return Arrays.stream(text.replace('’', '\'').trim().split("\\s+")).reduce((a, b) -> a + " " + b)
                .orElse("");
    }
}
