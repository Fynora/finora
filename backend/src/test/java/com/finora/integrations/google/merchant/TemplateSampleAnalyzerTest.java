package com.finora.integrations.google.merchant;

import com.finora.domain.Money;
import com.finora.integrations.google.SenderAuthenticationService;
import com.finora.integrations.google.TrustedSenderDomainService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Invented emails whose layouts copy what real receipts do (checked against real ones when this
 * was written): one long line with the label glued to a currency mark, a "Sub Total" before a
 * "Grand Total", whole rupees with the label in a different table cell, and receipts that print no
 * date at all.
 */
class TemplateSampleAnalyzerTest {

    private static final String DMARC_PASS =
            "mx.example.test; dmarc=pass (p=NONE) header.from=shop.example; dkim=pass header.i=@shop.example";

    private final TrustedSenderDomainService domains = Mockito.mock(TrustedSenderDomainService.class);
    private final MerchantEmailSanitizer sanitizer = new MerchantEmailSanitizer();
    private final TemplateSampleAnalyzer analyzer =
            new TemplateSampleAnalyzer(sanitizer, new SenderAuthenticationService(domains));

    private static String eml(String extraHeaders, String html) {
        return ("From: Shop Orders <noreply@shop.example>\n" + extraHeaders
                + "Content-Type: text/html; charset=utf-8\n\n" + html).replace("\n", "\r\n");
    }

    private static String authenticated(String html) {
        return eml("Authentication-Results: " + DMARC_PASS + "\nDate: Tue, 01 Sep 2026 20:30:00 +0000\n", html);
    }

    // ---- what is offered, and in what order -------------------------------------------------

    @Test
    @DisplayName("a receipt's total is offered first, with the label as it is written")
    void offersTheTotalFirst() {
        TemplateSampleAnalyzer.Analysis a = analyzer.analyze(authenticated(
                "<p>Aug 16, 2026 4:59 PM</p><p>Thanks for riding. Total &#8377;597.59 Booking Fee &#8377;4.90 "
                        + "Suggested fare &#8377;589.94</p>"));

        assertThat(a.amounts()).isNotEmpty();
        TemplateSampleAnalyzer.Candidate first = a.amounts().get(0);
        assertThat(first.pattern()).isEqualTo("Total ₹{amount}");
        assertThat(first.value()).isEqualTo("597.59");
        assertThat(first.likelyTotal()).isTrue();
    }

    @Test
    @DisplayName("a Grand Total is offered before a Sub Total that also ends in Total")
    void grandTotalBeatsSubTotal() {
        TemplateSampleAnalyzer.Analysis a = analyzer.analyze(authenticated(
                "<p>Sub Total : Rs.625.66 Discount : - Rs.100.00 Taxes Charges : Rs.55.68 Grand Total : Rs.581.00</p>"));

        assertThat(a.amounts().get(0).pattern()).isEqualTo("Grand Total : Rs.{amount}");
        assertThat(a.amounts().get(0).value()).isEqualTo("581.00");
        // The sub total is still offered, just not as the likely total.
        assertThat(a.amounts()).anySatisfy(c -> {
            assertThat(c.value()).isEqualTo("625.66");
            assertThat(c.likelyTotal()).isFalse();
        });
    }

    @Test
    @DisplayName("the amount paid is found by wording a few words back, when the label that finds it is only its last word")
    void wordingAFewWordsBackCounts() {
        // The label that finds 522 is just "Payment ₹", but the words before it say what it is.
        TemplateSampleAnalyzer.Analysis a = analyzer.analyze(authenticated(
                "<p>Delivered in 32 mins! &#8377;70</p><p>Paid Via Split Payment &#8377;522</p>"));

        assertThat(a.amounts().get(0).value()).isEqualTo("522");
        assertThat(a.amounts().get(0).likelyTotal()).isTrue();
        assertThat(a.amounts()).anySatisfy(c -> {
            assertThat(c.value()).isEqualTo("70");
            assertThat(c.likelyTotal()).isFalse();
        });
    }

    @Test
    @DisplayName("a fee belonging to the previous line does not count against the total that follows it")
    void aPreviousLinesFeeDoesNotDemoteTheTotal() {
        TemplateSampleAnalyzer.Analysis a = analyzer.analyze(authenticated(
                "<p>Handling Fee &#8377;12.00 Total &#8377;1491.00</p>"));

        assertThat(a.amounts().get(0).value()).isEqualTo("1491.00");
        assertThat(a.amounts().get(0).likelyTotal()).isTrue();
    }

    @Test
    @DisplayName("a label that also precedes an earlier figure is lengthened until it finds only its own")
    void lengthensAnAmbiguousLabel() {
        TemplateSampleAnalyzer.Analysis a = analyzer.analyze(authenticated(
                "<p>Delivery Fee Rs.10.00 Packing Fee Rs.20.00</p>"));

        // "Fee Rs." finds the delivery fee first, so it cannot stand for the packing fee: that one
        // needs the word before it too.
        assertThat(a.amounts()).anySatisfy(c -> {
            assertThat(c.value()).isEqualTo("10.00");
            assertThat(c.pattern()).isEqualTo("Fee Rs.{amount}");
        });
        assertThat(a.amounts()).anySatisfy(c -> {
            assertThat(c.value()).isEqualTo("20.00");
            assertThat(c.pattern()).isEqualTo("Packing Fee Rs.{amount}");
        });
    }

    @Test
    @DisplayName("every offered pattern, run the way production runs it, reads exactly the value it is offered for")
    void everyOfferedPatternReadsItsOwnValue() {
        TemplateSampleAnalyzer.Analysis a = analyzer.analyze(authenticated(
                "<table><tr><td>Bill Total</td>\n<td>\n&#8377;1,491.00</td></tr>"
                        + "<tr><td>Delivery Fee</td><td>&#8377;12.00</td></tr>"
                        + "<tr><td>Payment</td><td>Rs. 522</td></tr></table>"
                        + "<p>Order Date: August 12, 2026 and Delivered: 13/08/2026</p>"));

        assertThat(a.amounts()).isNotEmpty();
        assertThat(a.dates()).isNotEmpty();
        for (TemplateSampleAnalyzer.Candidate c : a.amounts()) {
            MerchantTemplate probe = new MerchantTemplate();
            probe.setAmountPattern(c.pattern());
            Matcher m = probe.compileAmountPattern().matcher(a.text());
            assertThat(m.find()).as(c.pattern()).isTrue();
            assertThat(m.group(1)).as(c.pattern()).isEqualTo(c.value());
        }
        for (TemplateSampleAnalyzer.Candidate c : a.dates()) {
            MerchantTemplate probe = new MerchantTemplate();
            probe.setDatePattern(c.pattern());
            Matcher m = probe.compileDatePattern().matcher(a.text());
            assertThat(m.find()).as(c.pattern()).isTrue();
            assertThat(m.group(1)).as(c.pattern()).isEqualTo(c.value());
        }
    }

    @Test
    @DisplayName("a label and its value in different cells, with whole rupees, are found")
    void findsAWholeRupeeAmountAcrossCells() {
        TemplateSampleAnalyzer.Analysis a = analyzer.analyze(authenticated(
                "<table><tr><td>Grand Total</td>\n<td>\n\n&#8377;522</td></tr></table>"));

        assertThat(a.amounts()).hasSize(1);
        assertThat(a.amounts().get(0).value()).isEqualTo("522");
        assertThat(a.amounts().get(0).pattern()).isEqualTo("Total ₹{amount}");
    }

    @Test
    @DisplayName("a bare number with no currency mark and no paise is not an amount")
    void aBareIntegerIsNotAnAmount() {
        TemplateSampleAnalyzer.Analysis a = analyzer.analyze(authenticated("<p>Qty 4 Order 12 Total 300</p>"));

        assertThat(a.amounts()).isEmpty();
        assertThat(a.problems()).anySatisfy(p -> assertThat(p).contains("No amount"));
    }

    @Test
    @DisplayName("a date with a year is offered with its label and parsed")
    void offersDatesWithTheirLabel() {
        TemplateSampleAnalyzer.Analysis a = analyzer.analyze(authenticated(
                "<p>payment date Aug 01, 2026 credited to card</p><p>Total Rs.10.00</p>"));

        assertThat(a.dates().get(0).pattern()).isEqualTo("date {date}");
        assertThat(a.dates().get(0).value()).isEqualTo("Aug 01, 2026");
        assertThat(a.dates().get(0).labelled()).isTrue();
    }

    // ---- what to say when the email cannot be read by a template ---------------------------

    @Test
    @DisplayName("an email with no date is told to use the day it arrived, in India time")
    void noDateSuggestsTheArrivalDay() {
        TemplateSampleAnalyzer.Analysis a = analyzer.analyze(authenticated("<p>Total &#8377;1491.00 delivered</p>"));

        assertThat(a.dates()).isEmpty();
        // 20:30 UTC on 1 September is 02:00 IST on 2 September.
        assertThat(a.receivedOn()).isEqualTo(LocalDate.of(2026, 9, 2));
        assertThat(a.arrivalDatePattern()).isEqualTo("{received}");
        assertThat(a.problems()).anySatisfy(p -> assertThat(p).contains("2026-09-02").contains("arrived"));
    }

    @Test
    @DisplayName("a Date header with a trailing comment is read; an unreadable one is null and says so")
    void readsTheDateHeader() {
        TemplateSampleAnalyzer.Analysis withComment = analyzer.analyze(eml(
                "Authentication-Results: " + DMARC_PASS + "\nDate: Tue, 1 Sep 2026 10:00:00 +0530 (IST)\n",
                "<p>Total &#8377;10.00</p>"));
        TemplateSampleAnalyzer.Analysis unreadable = analyzer.analyze(eml(
                "Authentication-Results: " + DMARC_PASS + "\nDate: sometime last week\n",
                "<p>Total &#8377;10.00</p>"));

        assertThat(withComment.receivedOn()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(unreadable.receivedOn()).isNull();
        assertThat(unreadable.problems()).anySatisfy(p -> assertThat(p).contains("could not be read"));
    }

    // ---- the sender -------------------------------------------------------------------------

    @Test
    @DisplayName("the authenticated domain and whether it is trusted come from the same check production uses")
    void reportsTheAuthenticatedDomain() {
        Mockito.when(domains.isActiveTrusted("shop.example")).thenReturn(true);

        TemplateSampleAnalyzer.Analysis trusted = analyzer.analyze(authenticated("<p>Total &#8377;10.00</p>"));

        assertThat(trusted.authenticatedDomain()).isEqualTo("shop.example");
        assertThat(trusted.domainIsTrusted()).isTrue();
        assertThat(trusted.senderVerdict()).isEqualTo("TRUSTED");
        assertThat(trusted.senderName()).isEqualTo("Shop Orders");
    }

    @Test
    @DisplayName("a domain not on the registry is reported as such, with the domain that would need trusting")
    void reportsAnUntrustedDomain() {
        Mockito.when(domains.isActiveTrusted("shop.example")).thenReturn(false);

        TemplateSampleAnalyzer.Analysis a = analyzer.analyze(authenticated("<p>Total &#8377;10.00</p>"));

        assertThat(a.authenticatedDomain()).isEqualTo("shop.example");
        assertThat(a.domainIsTrusted()).isFalse();
        assertThat(a.senderVerdict()).isEqualTo("DOMAIN_NOT_TRUSTED");
    }

    @Test
    @DisplayName("a message Gmail did not authenticate has no domain and the problem is stated")
    void reportsAnUnauthenticatedMessage() {
        TemplateSampleAnalyzer.Analysis a = analyzer.analyze(eml("", "<p>Total &#8377;10.00</p>"));

        assertThat(a.authenticatedDomain()).isNull();
        assertThat(a.domainIsTrusted()).isFalse();
        assertThat(a.problems()).anySatisfy(p -> assertThat(p).contains("did not authenticate"));
        // Still analysed: the admin can read the amounts and type the domain by hand.
        assertThat(a.amounts()).isNotEmpty();
    }

    // ---- receipt marker suggestions ---------------------------------------------------------

    @Test
    @DisplayName("marker suggestions are phrases that occur in the readable text exactly as written")
    void suggestsMarkersThatMatch() {
        TemplateSampleAnalyzer.Analysis a = analyzer.analyze(authenticated(
                "<p>Your order is confirmed.</p><p>Thank you for shopping with us.</p><p>Total &#8377;10.00</p>"));

        assertThat(a.receiptMarkerSuggestions()).contains("Your order is confirmed.");
        assertThat(a.receiptMarkerSuggestions()).allSatisfy(s -> {
            assertThat(a.text()).contains(s);
            assertThat(s).doesNotContainPattern("\\d");
        });
    }

    // ---- the seam: what the analyzer proposes, the pipeline reads ---------------------------

    @Test
    @DisplayName("a template built from the analyzer's own proposals parses the same email to the same values")
    void proposalsRoundTripThroughTheParser() {
        TemplateSampleAnalyzer.Analysis a = analyzer.analyze(authenticated(
                "<p>Your order is confirmed.</p><table><tr><td>Sub Total</td><td>&#8377;625.66</td></tr>"
                        + "<tr><td>Grand Total</td><td>\n&#8377;581.00</td></tr></table>"
                        + "<p>Order Date: August 12, 2026</p>"));

        MerchantTemplate template = new MerchantTemplate();
        template.setMerchantDomain("shop.example");
        template.setMerchantName("Shop");
        template.setReceiptMarker(a.receiptMarkerSuggestions().get(0));
        template.setAmountPattern(a.amounts().get(0).pattern());
        template.setDatePattern(a.dates().get(0).pattern());

        // The way the test screen and production both do it: the HTML through the sanitizer.
        SanitizedGmailMessage message = sanitizer.sanitize("id", "shop.example", a.html());
        ParserResult result = new TemplateEmailParser(Mockito.mock(MerchantTemplateRepository.class))
                .parse(message, template);

        assertThat(result.isParsed()).as(String.valueOf(result.reason())).isTrue();
        assertThat(result.receipt().amount()).isEqualTo(Money.of(new BigDecimal("581.00")));
        assertThat(result.receipt().transactionDate()).isEqualTo(LocalDate.of(2026, 8, 12));
    }

    @Test
    @DisplayName("a receipt with no date, dated by arrival, round-trips too")
    void arrivalDateRoundTrips() {
        TemplateSampleAnalyzer.Analysis a = analyzer.analyze(authenticated(
                "<p>Your order is delivered.</p><p>Total &#8377;1491.00</p>"));

        MerchantTemplate template = new MerchantTemplate();
        template.setMerchantDomain("shop.example");
        template.setMerchantName("Shop");
        template.setReceiptMarker(a.receiptMarkerSuggestions().get(0));
        template.setAmountPattern(a.amounts().get(0).pattern());
        template.setDatePattern(a.arrivalDatePattern());

        SanitizedGmailMessage message = sanitizer.sanitize("id", "shop.example", a.html(), a.receivedOn());
        ParserResult result = new TemplateEmailParser(Mockito.mock(MerchantTemplateRepository.class))
                .parse(message, template);

        assertThat(result.isParsed()).as(String.valueOf(result.reason())).isTrue();
        assertThat(result.receipt().transactionDate()).isEqualTo(LocalDate.of(2026, 9, 2));
    }

    // ---- input handling and bounds ----------------------------------------------------------

    @Test
    @DisplayName("plain text is analysed when there is no HTML part")
    void analysesAPlainTextOnlyEmail() {
        TemplateSampleAnalyzer.Analysis a = analyzer.analyze(
                ("Authentication-Results: " + DMARC_PASS + "\nContent-Type: text/plain\n\nYour order Total Rs.42.00")
                        .replace("\n", "\r\n"));

        assertThat(a.amounts()).extracting(TemplateSampleAnalyzer.Candidate::value).contains("42.00");
    }

    @Test
    @DisplayName("a file with no readable body is refused with an instruction")
    void refusesAFileWithNoBody() {
        assertThatThrownBy(() -> analyzer.analyze("Subject: nothing here\r\n\r\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Download original");
    }

    @Test
    @DisplayName("a huge email of nothing but amounts is answered quickly and offers a bounded list")
    void aHugeEmailIsBounded() {
        String html = "<p>" + "Item &#8377;1.00 ".repeat(60_000) + "</p>";

        long started = System.nanoTime();
        TemplateSampleAnalyzer.Analysis a = analyzer.analyze(authenticated(html));
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(a.amounts().size()).isLessThanOrEqualTo(25);
        assertThat(took).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("candidates are only ever values of the right kind")
    void candidatesAreTheRightKind() {
        TemplateSampleAnalyzer.Analysis a = analyzer.analyze(authenticated(
                "<p>Total &#8377;10.00 on Aug 16, 2026</p>"));

        List<String> amountValues = a.amounts().stream().map(TemplateSampleAnalyzer.Candidate::value).toList();
        assertThat(amountValues).allSatisfy(v -> assertThat(v).matches("[\\d,]+(\\.\\d{2})?"));
        assertThat(a.dates()).extracting(TemplateSampleAnalyzer.Candidate::value).containsExactly("Aug 16, 2026");
    }
}
