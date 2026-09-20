package com.finora.integrations.google.merchant;

import com.finora.domain.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase C5.3 — Myntra, hand-written. See {@link MyntraEmailParser}'s own doc comment for why this
 * merchant stays a class rather than a {@link TemplateEmailParser} row like Zomato (C5.3's other
 * new merchant): the return/exchange/refund exclusion this suite specifically exercises.
 */
class MyntraEmailParserTest {

    private final MerchantEmailSanitizer sanitizer = new MerchantEmailSanitizer();
    private final MyntraEmailParser parser = new MyntraEmailParser();

    @Test
    void canParseOnlyClaimsMyntrasAuthenticatedDomain() {
        assertThat(parser.canParse("myntra.com")).isTrue();
        assertThat(parser.canParse("myntra.attacker.example")).isFalse();
        assertThat(parser.canParse("amazon.in")).isFalse();
    }

    @Test
    @DisplayName("an order confirmation is parsed into a receipt with the right amount and date")
    void shouldParseMyntraOrderConfirmation() {
        SanitizedGmailMessage message = load("order-receipt-1.html", "msg-1");

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        ParsedReceipt receipt = result.receipt();
        assertThat(receipt.gmailMessageId()).isEqualTo("msg-1");
        assertThat(receipt.merchantDomain()).isEqualTo("myntra.com");
        assertThat(receipt.amount()).isEqualTo(Money.of(new BigDecimal("1699.00")));
        assertThat(receipt.transactionDate()).isEqualTo(LocalDate.of(2026, 8, 9));
    }

    /** Second fixture uses a dd/MM/yyyy date, unlike the first's "MMMM d, yyyy" -- both must
     *  work, matching Amazon's and Ola's own multi-format coverage. */
    @Test
    @DisplayName("a differently-templated order confirmation still parses")
    void shouldParseMyntraOrderConfirmationInAnAlternateTemplate() {
        SanitizedGmailMessage message = load("order-receipt-2.html", "msg-2");

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        assertThat(result.receipt().amount()).isEqualTo(Money.of(new BigDecimal("2499.00")));
        assertThat(result.receipt().transactionDate()).isEqualTo(LocalDate.of(2026, 8, 11));
    }

    @Test
    @DisplayName("a marketing email is recognised as not-a-receipt, not as a parse failure")
    void shouldIgnoreMyntraMarketingEmail() {
        SanitizedGmailMessage message = load("marketing-email.html", "msg-3");

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.NOT_A_RECEIPT);
        assertThat(result.receipt()).isNull();
    }

    /** The exclusion this whole class exists for: a return notification quotes the original
     *  order's own "Order Confirmed" / total / date text inline, and must still not be staged as
     *  a fresh purchase. */
    @Test
    @DisplayName("a return notification is not-a-receipt even though it quotes order text")
    void shouldIgnoreReturnNotificationDespiteQuotedOrderText() {
        SanitizedGmailMessage message = load("return-notification.html", "msg-4");

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.NOT_A_RECEIPT);
        assertThat(result.receipt()).isNull();
    }

    @Test
    @DisplayName("a receipt-shaped email with an unparseable total is malformed, not ignored")
    void shouldRejectMalformedAmount() {
        String html = "<p>Order Confirmed</p><p>Order Date: 2026-08-01</p>"
                + "<p>Order Total: [[AMOUNT_PLACEHOLDER]]</p>";
        SanitizedGmailMessage message = sanitizer.sanitize("msg-5", "myntra.com", html);

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.MALFORMED);
        assertThat(result.receipt()).isNull();
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    @DisplayName("an implausibly long amount is rejected outright, not truncated to a wrong value")
    void anOversizedAmountIsRejectedNotSilentlyTruncated() {
        String hugeDigitRun = "1".repeat(25);
        String html = "<p>Order Confirmed</p><p>Order Date: 2026-08-01</p>"
                + "<p>Order Total: Rs. " + hugeDigitRun + ".00</p>";
        SanitizedGmailMessage message = sanitizer.sanitize("msg-6", "myntra.com", html);

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.MALFORMED);
        assertThat(result.receipt()).isNull();
    }

    /** Extraction fidelity, not business judgment -- same reasoning as AmazonEmailParserTest's
     *  and OlaEmailParserTest's identical cases. */
    @Test
    @DisplayName("a fully-discounted order still parses honestly; the validator decides staging separately")
    void aZeroTotalStillParses() {
        String html = "<p>Order Confirmed</p><p>Order Date: 2026-08-01</p><p>Order Total: Rs. 0.00</p>";
        SanitizedGmailMessage message = sanitizer.sanitize("msg-7", "myntra.com", html);

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        assertThat(result.receipt().amount()).isEqualTo(Money.ZERO);
    }

    // ---------------------------------------------------------------------------------------
    // The wording real Myntra mail uses: "Your [fwd / M-Express] Order Is Confirmed on Mon, 13 Jul",
    // an amount labelled "Net Paid", and no year. Fixtures are invented but copy that wording.
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a current confirmation is parsed: amount from Net Paid, year recovered from the weekday")
    void parsesTheCurrentLayout() {
        // 13 July 2026 is a Monday.
        SanitizedGmailMessage message = loadArrivedOn("order-confirmed-current-layout.html", "msg-20",
                LocalDate.of(2026, 7, 13));

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        assertThat(result.receipt().amount()).isEqualTo(Money.of(new BigDecimal("496.00")));
        assertThat(result.receipt().transactionDate()).isEqualTo(LocalDate.of(2026, 7, 13));
        assertThat(result.receipt().merchantDomain()).isEqualTo("myntra.com");
    }

    @Test
    @DisplayName("the M-Express and fwd variants of the confirmation wording are read too")
    void parsesTheMExpressAndFwdVariants() {
        // 21 November 2025 is a Friday; 14 April 2025 is a Monday.
        ParserResult mExpress = parser.parse(loadArrivedOn("order-confirmed-mexpress.html", "msg-21",
                LocalDate.of(2025, 11, 21)));
        ParserResult fwd = parser.parse(loadArrivedOn("order-confirmed-fwd.html", "msg-22",
                LocalDate.of(2025, 4, 14)));

        assertThat(mExpress.isParsed()).isTrue();
        assertThat(mExpress.receipt().amount()).isEqualTo(Money.of(new BigDecimal("1317.00")));
        assertThat(mExpress.receipt().transactionDate()).isEqualTo(LocalDate.of(2025, 11, 21));
        assertThat(fwd.isParsed()).isTrue();
        assertThat(fwd.receipt().amount()).isEqualTo(Money.of(new BigDecimal("1019.00")));
        assertThat(fwd.receipt().transactionDate()).isEqualTo(LocalDate.of(2025, 4, 14));
    }

    @Test
    @DisplayName("a shipped update, which also shows a total and a date, is not a purchase")
    void aShippedUpdateIsNotAReceipt() {
        SanitizedGmailMessage message = loadArrivedOn("shipped-update.html", "msg-23", LocalDate.of(2026, 7, 15));

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.NOT_A_RECEIPT);
    }

    @Test
    @DisplayName("a December order read in January belongs to the year that just ended")
    void aDecemberOrderReadInJanuaryIsLastYear() {
        // 26 December 2025 is a Friday; the email arrived on 2 January 2026.
        String html = "<p>Your Order Is Confirmed on Fri, 26 Dec</p><p>Net Paid &#8377;300.00</p>";
        SanitizedGmailMessage message = sanitizer.sanitize("msg-24", "myntra.com", html, LocalDate.of(2026, 1, 2));

        ParserResult result = parser.parse(message);

        assertThat(result.receipt().transactionDate()).isEqualTo(LocalDate.of(2025, 12, 26));
    }

    @Test
    @DisplayName("a weekday that does not match any nearby year is malformed, not dated by guess")
    void aWeekdayThatFitsNoYearIsMalformed() {
        // 13 July 2026 is a Monday, not a Tuesday.
        String html = "<p>Your Order Is Confirmed on Tue, 13 Jul</p><p>Net Paid &#8377;300.00</p>";
        SanitizedGmailMessage message = sanitizer.sanitize("msg-25", "myntra.com", html, LocalDate.of(2026, 7, 13));

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.MALFORMED);
        assertThat(result.reason()).contains("order date");
    }

    @Test
    @DisplayName("Net Paid is the amount when a wallet or coupon paid part of the total")
    void netPaidBeatsTotalAmount() {
        String html = "<p>Your Order Is Confirmed on Mon, 13 Jul</p><p>Total Amount &#8377;496.00</p>"
                + "<p>Net Paid &#8377;400.00</p>";
        SanitizedGmailMessage message = sanitizer.sanitize("msg-26", "myntra.com", html, LocalDate.of(2026, 7, 13));

        ParserResult result = parser.parse(message);

        assertThat(result.receipt().amount()).isEqualTo(Money.of(new BigDecimal("400.00")));
    }

    @Test
    @DisplayName("Total Amount is used when there is no Net Paid line")
    void totalAmountIsTheFallback() {
        String html = "<p>Your Order Is Confirmed on Mon, 13 Jul</p><p>Total Amount &#8377;496.00</p>";
        SanitizedGmailMessage message = sanitizer.sanitize("msg-27", "myntra.com", html, LocalDate.of(2026, 7, 13));

        ParserResult result = parser.parse(message);

        assertThat(result.receipt().amount()).isEqualTo(Money.of(new BigDecimal("496.00")));
    }

    @Test
    @DisplayName("a confirmation with no amount at all is malformed")
    void aConfirmationWithNoAmountIsMalformed() {
        String html = "<p>Your Order Is Confirmed on Mon, 13 Jul</p><p>Thanks for shopping.</p>";
        SanitizedGmailMessage message = sanitizer.sanitize("msg-28", "myntra.com", html, LocalDate.of(2026, 7, 13));

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.MALFORMED);
    }

    @Test
    @DisplayName("a return notice is still excluded even when it quotes the confirmation wording")
    void aReturnNoticeQuotingTheConfirmationIsStillExcluded() {
        String html = "<p>Return Initiated for your order. Your Order Is Confirmed on Mon, 13 Jul</p>"
                + "<p>Net Paid &#8377;496.00</p>";
        SanitizedGmailMessage message = sanitizer.sanitize("msg-29", "myntra.com", html, LocalDate.of(2026, 7, 20));

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.NOT_A_RECEIPT);
    }

    private SanitizedGmailMessage loadArrivedOn(String fixture, String gmailMessageId, LocalDate arrived) {
        try {
            String html = Files.readString(Path.of("src/test/resources/gmail/myntra", fixture));
            return sanitizer.sanitize(gmailMessageId, "myntra.com", html, arrived);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private SanitizedGmailMessage load(String fixture, String gmailMessageId) {
        try {
            String html = Files.readString(Path.of("src/test/resources/gmail/myntra", fixture));
            return sanitizer.sanitize(gmailMessageId, "myntra.com", html);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
