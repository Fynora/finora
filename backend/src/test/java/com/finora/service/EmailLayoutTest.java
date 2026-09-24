package com.finora.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailLayoutTest {

    private static final String SUPPORT_ADDRESS = "help@example.test";

    @Test
    void wrap_includesTheHeadingAndBody() {
        String html = EmailLayout.wrap("Statement under review",
                "We will notify you once it is ready.", true, SUPPORT_ADDRESS);

        assertThat(html).contains("Statement under review");
        assertThat(html).contains("We will notify you once it is ready.");
    }

    @Test
    void wrap_carriesTheBrandWordmark() {
        String html = EmailLayout.wrap("Title", "Body", true, SUPPORT_ADDRESS);

        assertThat(html).contains("FYNORA");
    }

    @Test
    void wrap_invitesAReplyWhenSentFromSupport() {
        String html = EmailLayout.wrap("Title", "Body", true, SUPPORT_ADDRESS);

        assertThat(html).contains("reply to this email");
        assertThat(html).doesNotContain(SUPPORT_ADDRESS);
    }

    @Test
    void wrap_pointsToTheGivenSupportAddressWhenNotSentFromSupport() {
        String html = EmailLayout.wrap("Title", "Body", false, SUPPORT_ADDRESS);

        assertThat(html).contains(SUPPORT_ADDRESS);
        assertThat(html).contains("mailto:" + SUPPORT_ADDRESS);
        assertThat(html).doesNotContain("reply to this email");
    }

    @Test
    void wrap_escapesHtmlInTheHeadingAndBody() {
        String html = EmailLayout.wrap("<script>alert(1)</script>",
                "5 > 3 & <b>bold</b> isn't real markup here", true, SUPPORT_ADDRESS);

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).contains("&amp;");
        assertThat(html).contains("&lt;b&gt;bold&lt;/b&gt;");
    }

    @Test
    void wrap_turnsNewlinesIntoLineBreaksInTheBody() {
        String html = EmailLayout.wrap("Title", "Line one\nLine two", true, SUPPORT_ADDRESS);

        assertThat(html).contains("Line one<br>Line two");
    }

    // -- new, rich-HTML overload --

    @Test
    void richWrap_rendersACtaButtonWhenGiven() {
        String html = EmailLayout.wrap("Reset your password", "<p>Click below.</p>",
                new EmailLayout.CtaButton("Reset Password", "https://app.fynora.net/reset?token=abc"),
                EmailLayout.Footer.SUPPORT_LINK, SUPPORT_ADDRESS);

        assertThat(html).contains("https://app.fynora.net/reset?token=abc");
        assertThat(html).contains("Reset Password");
    }

    @Test
    void richWrap_omitsTheButtonTableWhenCtaIsNull() {
        String html = EmailLayout.wrap("Title", "<p>Body</p>", null,
                EmailLayout.Footer.SUPPORT_LINK, SUPPORT_ADDRESS);

        assertThat(html).doesNotContain("border-radius:8px;background:#262A33");
    }

    @Test
    void richWrap_doesNotEscapeTheTrustedBodyHtml() {
        String html = EmailLayout.wrap("Title", "<p>Hi <strong>Jordan</strong>,</p>", null,
                EmailLayout.Footer.NONE, SUPPORT_ADDRESS);

        assertThat(html).contains("<strong>Jordan</strong>");
    }

    @Test
    void richWrap_supportReplyFooterInvitesAReply() {
        String html = EmailLayout.wrap("Title", "<p>Body</p>", null,
                EmailLayout.Footer.SUPPORT_REPLY, SUPPORT_ADDRESS);

        assertThat(html).contains("reply to this email");
        assertThat(html).doesNotContain("mailto:" + SUPPORT_ADDRESS);
    }

    @Test
    void richWrap_supportLinkFooterPointsToTheGivenAddress() {
        String html = EmailLayout.wrap("Title", "<p>Body</p>", null,
                EmailLayout.Footer.SUPPORT_LINK, SUPPORT_ADDRESS);

        assertThat(html).contains("mailto:" + SUPPORT_ADDRESS);
    }

    @Test
    void richWrap_noneFooterOmitsBothTheReplyAndMailtoLines() {
        String html = EmailLayout.wrap("Title", "<p>Body</p>", null,
                EmailLayout.Footer.NONE, SUPPORT_ADDRESS);

        assertThat(html).doesNotContain("reply to this email");
        assertThat(html).doesNotContain("mailto:");
    }

    @Test
    void richWrap_alwaysIncludesTheLegalLineRegardlessOfFooterChoice() {
        String html = EmailLayout.wrap("Title", "<p>Body</p>", null,
                EmailLayout.Footer.NONE, SUPPORT_ADDRESS);

        assertThat(html).contains("Fynora Technovation LLP");
        assertThat(html).contains(String.valueOf(java.time.Year.now().getValue()));
    }

    @Test
    void richWrap_escapesTheHeadingButNotTheBody() {
        String html = EmailLayout.wrap("<script>bad</script>", "<p>trusted</p>", null,
                EmailLayout.Footer.NONE, SUPPORT_ADDRESS);

        assertThat(html).doesNotContain("<script>bad</script>");
        assertThat(html).contains("&lt;script&gt;bad&lt;/script&gt;");
        assertThat(html).contains("<p>trusted</p>");
    }

    @Test
    void richWrap_withAManageUrl_addsAnOptOutLineLinkingThere() {
        String html = EmailLayout.wrap("Title", "<p>Body</p>", null, EmailLayout.Footer.SUPPORT_REPLY,
                SUPPORT_ADDRESS, "https://app.example.test/app/settings?tab=notifications");

        assertThat(html).contains("Turn them off in your <a href=\"https://app.example.test/app/settings?tab=notifications\"");
        assertThat(html).contains("reply to this email");
        assertThat(html).contains("Fynora Technovation LLP");
    }

    @Test
    void richWrap_withoutAManageUrl_rendersExactlyWhatItDidBefore() {
        String withFiveArgs = EmailLayout.wrap("Title", "<p>Body</p>", null, EmailLayout.Footer.SUPPORT_LINK,
                SUPPORT_ADDRESS);
        String withNullUrl = EmailLayout.wrap("Title", "<p>Body</p>", null, EmailLayout.Footer.SUPPORT_LINK,
                SUPPORT_ADDRESS, null);

        assertThat(withFiveArgs).isEqualTo(withNullUrl);
        assertThat(withFiveArgs).doesNotContain("notification settings");
    }

    @Test
    void legacyWrap_withAManageUrl_addsTheOptOutLineToo() {
        String html = EmailLayout.wrap("Title", "Body", false, SUPPORT_ADDRESS,
                "https://app.example.test/app/settings?tab=notifications");

        assertThat(html).contains("notification settings");
        assertThat(EmailLayout.wrap("Title", "Body", false, SUPPORT_ADDRESS)).doesNotContain("notification settings");
    }

    @Test
    void richWrap_escapesTheManageUrl() {
        String html = EmailLayout.wrap("Title", "<p>Body</p>", null, EmailLayout.Footer.NONE,
                SUPPORT_ADDRESS, "https://x.test/\"><script>");

        assertThat(html).doesNotContain("\"><script>");
        assertThat(html).contains("&quot;&gt;&lt;script&gt;");
    }
}
