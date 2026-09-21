package com.finora.integrations.google.merchant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What an admin's downloaded email must turn into. The bodies here are invented; the layouts mirror
 * what real mail does (multipart/alternative, quoted-printable HTML with soft line breaks, base64,
 * folded headers, attachments), and the decoder was also compared with Python's own email decoding
 * over real receipts before this was written.
 */
class MimeMessageReaderTest {

    private static String eml(String headers, String body) {
        return headers.replace("\n", "\r\n") + "\r\n\r\n" + body.replace("\n", "\r\n");
    }

    @Test
    @DisplayName("a multipart/alternative message yields its HTML and its plain-text part")
    void readsBothAlternatives() {
        String raw = eml("From: Shop <noreply@shop.example>\nContent-Type: multipart/alternative; boundary=\"b1\"",
                "--b1\nContent-Type: text/plain; charset=utf-8\n\nPlain body\n"
                        + "--b1\nContent-Type: text/html; charset=utf-8\n\n<p>Html body</p>\n--b1--\n");

        MimeMessageReader.Parsed parsed = MimeMessageReader.read(raw);

        assertThat(parsed.html().strip()).isEqualTo("<p>Html body</p>");
        assertThat(parsed.text().strip()).isEqualTo("Plain body");
    }

    @Test
    @DisplayName("quoted-printable is decoded, including =XX bytes, soft line breaks and UTF-8 rupee signs")
    void decodesQuotedPrintable() {
        String qp = "<td style=3D\"color:red\">Total =E2=82=B9597.59</td>=\n<td>next</td>";
        String raw = eml("Content-Type: text/html; charset=utf-8\nContent-Transfer-Encoding: quoted-printable", qp);

        String html = MimeMessageReader.read(raw).html();

        assertThat(html).contains("<td style=\"color:red\">Total ₹597.59</td><td>next</td>");
    }

    @Test
    @DisplayName("base64 is decoded in the part's charset")
    void decodesBase64() {
        String encoded = Base64.getMimeEncoder().encodeToString("<p>Total ₹120.00</p>".getBytes(StandardCharsets.UTF_8));
        String raw = eml("Content-Type: text/html; charset=utf-8\nContent-Transfer-Encoding: base64", encoded);

        assertThat(MimeMessageReader.read(raw).html()).isEqualTo("<p>Total ₹120.00</p>");
    }

    @Test
    @DisplayName("a declared non-UTF-8 charset is honoured")
    void honoursTheDeclaredCharset() {
        // 0xE9 is 'é' in ISO-8859-1, and is not valid UTF-8 on its own.
        String raw = eml("Content-Type: text/plain; charset=iso-8859-1\nContent-Transfer-Encoding: quoted-printable",
                "Caf=E9 receipt");

        assertThat(MimeMessageReader.read(raw).text()).isEqualTo("Café receipt");
    }

    @Test
    @DisplayName("an attachment is never taken for the body, and nested multiparts are walked")
    void skipsAttachmentsAndWalksNestedParts() {
        String raw = eml("Content-Type: multipart/mixed; boundary=\"outer\"",
                "--outer\nContent-Type: multipart/alternative; boundary=\"inner\"\n\n"
                        + "--inner\nContent-Type: text/html; charset=utf-8\n\n<p>Real body</p>\n--inner--\n"
                        + "--outer\nContent-Type: text/html; name=\"invoice.html\"\n"
                        + "Content-Disposition: attachment; filename=\"invoice.html\"\n\n<p>Attached invoice</p>\n"
                        + "--outer--\n");

        MimeMessageReader.Parsed parsed = MimeMessageReader.read(raw);

        assertThat(parsed.html().strip()).isEqualTo("<p>Real body</p>");
    }

    @Test
    @DisplayName("a multipart whose closing delimiter is missing still yields its last part")
    void readsTheLastPartOfAnUnterminatedMultipart() {
        // The HTML part usually comes last, so dropping it would leave nothing to read.
        String raw = eml("Content-Type: multipart/alternative; boundary=\"b1\"",
                "--b1\nContent-Type: text/plain\n\nPlain body\n"
                        + "--b1\nContent-Type: text/html; charset=utf-8\n\n<p>Html body</p>\n");

        MimeMessageReader.Parsed parsed = MimeMessageReader.read(raw);

        assertThat(parsed.html().strip()).isEqualTo("<p>Html body</p>");
        assertThat(parsed.text().strip()).isEqualTo("Plain body");
    }

    @Test
    @DisplayName("the first HTML part wins when there are several")
    void firstHtmlPartWins() {
        String raw = eml("Content-Type: multipart/mixed; boundary=\"b\"",
                "--b\nContent-Type: text/html\n\n<p>First</p>\n--b\nContent-Type: text/html\n\n<p>Second</p>\n--b--\n");

        assertThat(MimeMessageReader.read(raw).html().strip()).isEqualTo("<p>First</p>");
    }

    @Test
    @DisplayName("headers are unfolded, matched case-insensitively, and keep message order")
    void readsHeaders() {
        String raw = eml("Authentication-Results: mx.example.test;\n dkim=pass header.i=@shop.example;\n"
                        + "\tdmarc=pass header.from=shop.example\nX-Thing: one\nx-thing: two\nContent-Type: text/plain",
                "body");

        MimeMessageReader.Parsed parsed = MimeMessageReader.read(raw);

        assertThat(parsed.firstHeader("AUTHENTICATION-RESULTS"))
                .isEqualTo("mx.example.test; dkim=pass header.i=@shop.example; dmarc=pass header.from=shop.example");
        assertThat(parsed.headers().get("x-thing")).containsExactly("one", "two");
    }

    @Test
    @DisplayName("a message with no Content-Type is plain text")
    void defaultsToPlainText() {
        assertThat(MimeMessageReader.read(eml("Subject: hi", "just text")).text()).isEqualTo("just text");
    }

    @Test
    @DisplayName("a multipart with no boundary, or an undecodable body, yields no body rather than failing")
    void malformedInputYieldsNoBody() {
        MimeMessageReader.Parsed noBoundary = MimeMessageReader.read(eml("Content-Type: multipart/mixed", "x"));
        MimeMessageReader.Parsed badBase64 = MimeMessageReader.read(
                eml("Content-Type: text/html\nContent-Transfer-Encoding: base64", "@@@ not base64 @@@"));

        assertThat(noBoundary.html()).isNull();
        assertThat(badBase64.html()).isNull();
    }

    @Test
    @DisplayName("encoded-word headers such as a display name are decoded")
    void decodesEncodedWords() {
        assertThat(MimeMessageReader.decodeHeaderWords("=?UTF-8?B?U3dpZ2d5IE9yZGVycw==?= <a@b.example>"))
                .isEqualTo("Swiggy Orders <a@b.example>");
        assertThat(MimeMessageReader.decodeHeaderWords("=?UTF-8?Q?Caf=C3=A9_Shop?= <a@b.example>"))
                .isEqualTo("Café Shop <a@b.example>");
        assertThat(MimeMessageReader.decodeHeaderWords("Plain Name <a@b.example>")).isEqualTo("Plain Name <a@b.example>");
        assertThat(MimeMessageReader.decodeHeaderWords("=?UTF-8?B?bad!!?= x")).contains("x");
    }

    @Test
    @DisplayName("a pathological nesting is bounded, not followed forever")
    void deepNestingIsBounded() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            body.append("--b").append(i).append("\nContent-Type: multipart/mixed; boundary=\"b").append(i + 1).append("\"\n\n");
        }
        String raw = eml("Content-Type: multipart/mixed; boundary=\"b0\"", body.toString());

        assertThat(MimeMessageReader.read(raw).html()).isNull();
    }

    @Test
    @DisplayName("an empty or oversized email is refused with a reason")
    void refusesEmptyAndOversized() {
        assertThatThrownBy(() -> MimeMessageReader.read("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MimeMessageReader.read(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MimeMessageReader.read("x".repeat(MimeMessageReader.MAX_RAW_CHARS + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }

    @Test
    @DisplayName("a lone '=' that starts no escape is kept as written")
    void keepsAStrayEqualsSign() {
        assertThat(new String(MimeMessageReader.decodeQuotedPrintable("a=b =4 c=ZZ"), StandardCharsets.UTF_8))
                .isEqualTo("a=b =4 c=ZZ");
    }
}
