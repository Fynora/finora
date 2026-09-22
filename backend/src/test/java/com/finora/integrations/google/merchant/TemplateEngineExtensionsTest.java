package com.finora.integrations.google.merchant;

import com.finora.domain.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Three things a real receipt needed that the template engine did not do, each added so an admin can
 * author a template from an email as it really reads:
 * <ul>
 *   <li>whitespace in a pattern matches any whitespace in the email (a label and its value are often
 *       in different table cells, so the sanitized text has a line break between them);</li>
 *   <li>paise are optional (some merchants write whole rupees);</li>
 *   <li>a date pattern of exactly {@code {received}} dates the receipt by the day the email arrived
 *       (some merchants print no date at all).</li>
 * </ul>
 * Everything a pattern matched before must still match the same value; the existing template tests
 * are the regression check for that, and the "still" tests here pin the boundaries.
 */
class TemplateEngineExtensionsTest {

    private final TemplateEmailParser parser = new TemplateEmailParser(mock(MerchantTemplateRepository.class));

    private static MerchantTemplate template(String marker, String amountPattern, String datePattern) {
        MerchantTemplate template = new MerchantTemplate();
        template.setMerchantDomain("shop.example");
        template.setMerchantName("Shop");
        template.setReceiptMarker(marker);
        template.setAmountPattern(amountPattern);
        template.setDatePattern(datePattern);
        template.setEnabled(true);
        return template;
    }

    private static SanitizedGmailMessage message(String plainText, LocalDate receivedOn) {
        return new SanitizedGmailMessage("msg", "shop.example", "<p>x</p>", plainText, receivedOn);
    }

    private ParserResult parse(String text, String amountPattern, String datePattern, LocalDate receivedOn) {
        return parser.parse(message(text, receivedOn), template("Your order", amountPattern, datePattern));
    }

    // ---- whitespace -----------------------------------------------------------------------

    @Test
    @DisplayName("a space in a pattern matches a line break in the email")
    void aSpaceMatchesALineBreak() {
        String text = "Your order\nItem Total\n ₹17.98\nOrder Date: August 12, 2026";

        ParserResult result = parse(text, "Item Total ₹{amount}", "Order Date: {date}", null);

        assertThat(result.isParsed()).isTrue();
        assertThat(result.receipt().amount()).isEqualTo(Money.of(new BigDecimal("17.98")));
    }

    @Test
    @DisplayName("a single space still matches a single space, and text with no gap still needs none")
    void existingSpacingStillMatches() {
        assertThat(parse("Your order Total: Rs. 255.00 Trip Date: August 12, 2026",
                "Total: Rs. {amount}", "Trip Date: {date}", null).receipt().amount())
                .isEqualTo(Money.of(new BigDecimal("255.00")));
        assertThat(parse("Your order Grand Total : Rs.581.00 Order Date: August 12, 2026",
                "Grand Total : Rs.{amount}", "Order Date: {date}", null).receipt().amount())
                .isEqualTo(Money.of(new BigDecimal("581.00")));
    }

    @Test
    @DisplayName("a pattern that has no space still does not match text that has one")
    void noSpaceInThePatternMeansNoSpaceInTheEmail() {
        // "Total₹{amount}" was never a match for "Total ₹100.00", and still is not.
        ParserResult result = parse("Your order Total ₹100.00 Order Date: August 12, 2026",
                "Total₹{amount}", "Order Date: {date}", null);

        assertThat(result.isParsed()).isFalse();
    }

    @Test
    @DisplayName("regex characters in a pattern are still literal")
    void regexCharactersStayLiteral() {
        ParserResult result = parse("Your order Total (incl. tax) : Rs. 90.00 Order Date: August 12, 2026",
                "Total (incl. tax) : Rs. {amount}", "Order Date: {date}", null);

        assertThat(result.isParsed()).isTrue();
        assertThat(result.receipt().amount()).isEqualTo(Money.of(new BigDecimal("90.00")));
    }

    // ---- amounts --------------------------------------------------------------------------

    @Test
    @DisplayName("a whole-rupee amount is read")
    void readsWholeRupees() {
        ParserResult result = parse("Your order Paid ₹522 on the card Order Date: August 12, 2026",
                "Paid ₹{amount}", "Order Date: {date}", null);

        assertThat(result.isParsed()).isTrue();
        assertThat(result.receipt().amount()).isEqualTo(Money.of(new BigDecimal("522")));
    }

    @Test
    @DisplayName("an amount with paise still reads whole, with thousands separators")
    void readsPaiseAndSeparators() {
        ParserResult result = parse("Your order Total ₹1,491.00 Order Date: August 12, 2026",
                "Total ₹{amount}", "Order Date: {date}", null);

        assertThat(result.receipt().amount()).isEqualTo(Money.of(new BigDecimal("1491.00")));
    }

    @Test
    @DisplayName("an amount that does not end cleanly is not read as a shorter number")
    void refusesATruncatedRead() {
        // One decimal place, and three: reading either as 1499 / 1,499.00 would be a wrong amount.
        assertThat(parse("Your order Total ₹1499.5 Order Date: August 12, 2026",
                "Total ₹{amount}", "Order Date: {date}", null).isParsed()).isFalse();
        assertThat(parse("Your order Total ₹1,499.001 Order Date: August 12, 2026",
                "Total ₹{amount}", "Order Date: {date}", null).isParsed()).isFalse();
        // Backing off to the digits before the first comma would read 1,499.5 as 1.
        assertThat(parse("Your order Total ₹1,499.5 Order Date: August 12, 2026",
                "Total ₹{amount}", "Order Date: {date}", null).isParsed()).isFalse();
        assertThat(parse("Your order Total ₹12,34,567.5 Order Date: August 12, 2026",
                "Total ₹{amount}", "Order Date: {date}", null).isParsed()).isFalse();
    }

    @Test
    @DisplayName("Indian digit grouping is read whole")
    void readsIndianGrouping() {
        ParserResult result = parse("Your order Total ₹12,34,567 Order Date: August 12, 2026",
                "Total ₹{amount}", "Order Date: {date}", null);

        assertThat(result.receipt().amount()).isEqualTo(Money.of(new BigDecimal("1234567")));
    }

    @Test
    @DisplayName("a sentence-ending full stop after a whole-rupee amount is not part of it")
    void aFullStopAfterAnAmountIsNotPartOfIt() {
        ParserResult result = parse("Your order Total ₹500. Thank you. Order Date: August 12, 2026",
                "Total ₹{amount}", "Order Date: {date}", null);

        assertThat(result.isParsed()).isTrue();
        assertThat(result.receipt().amount()).isEqualTo(Money.of(new BigDecimal("500")));
    }

    // ---- dates ----------------------------------------------------------------------------

    @Test
    @DisplayName("a very long single word in the email does not stall date matching")
    void aHugeWordDoesNotStallDateMatching() {
        // With an unbounded run of letters in the date shape this took 35 seconds for 120,000 letters,
        // because the engine scanned to the end of the word from every position inside it.
        String text = "Your order " + "a".repeat(120_000) + " Total ₹1.00 Order Date: August 12, 2026";

        long started = System.nanoTime();
        ParserResult result = parse(text, "Total ₹{amount}", "Order Date: {date}", null);
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(result.isParsed()).isTrue();
        assertThat(millis).isLessThan(2_000);
    }

    @Test
    @DisplayName("every month name, the longest included, is still read in both date layouts")
    void readsEveryMonthName() {
        for (String month : new String[] {"January", "February", "March", "April", "May", "June", "July",
                "August", "September", "October", "November", "December"}) {
            assertThat(parse("Your order Total ₹1.00 Order Date: " + month + " 5, 2026",
                    "Total ₹{amount}", "Order Date: {date}", null).isParsed()).as(month).isTrue();
            assertThat(parse("Your order Total ₹1.00 Order Date: 5 " + month + " 2026",
                    "Total ₹{amount}", "Order Date: {date}", null).isParsed()).as(month).isTrue();
        }
    }

    // ---- arrival date ---------------------------------------------------------------------

    @Test
    @DisplayName("{received} dates the receipt by the day the email arrived")
    void datesByTheArrivalDay() {
        ParserResult result = parse("Your order Total ₹1491.00 delivered", "Total ₹{amount}",
                MerchantTemplate.RECEIVED_PLACEHOLDER, LocalDate.of(2026, 9, 2));

        assertThat(result.isParsed()).isTrue();
        assertThat(result.receipt().transactionDate()).isEqualTo(LocalDate.of(2026, 9, 2));
    }

    @Test
    @DisplayName("{received} with an unknown arrival day is malformed, never dated by the clock")
    void anUnknownArrivalDayIsMalformed() {
        ParserResult result = parse("Your order Total ₹1491.00 delivered", "Total ₹{amount}",
                MerchantTemplate.RECEIVED_PLACEHOLDER, null);

        assertThat(result.status()).isEqualTo(ParserResult.Status.MALFORMED);
        assertThat(result.reason()).contains("arrived");
    }

    @Test
    @DisplayName("a template that reads a date from the text ignores the arrival day")
    void aTextDateWinsOverTheArrivalDay() {
        ParserResult result = parse("Your order Total ₹10.00 Order Date: August 12, 2026", "Total ₹{amount}",
                "Order Date: {date}", LocalDate.of(2026, 9, 2));

        assertThat(result.receipt().transactionDate()).isEqualTo(LocalDate.of(2026, 8, 12));
    }

    @Test
    @DisplayName("only exactly {received} means the arrival day; anything else is an ordinary date pattern")
    void receivedMustBeExact() {
        assertThat(template("m", "Total ₹{amount}", "{received}").usesArrivalDate()).isTrue();
        assertThat(template("m", "Total ₹{amount}", "  {received}  ").usesArrivalDate()).isTrue();
        assertThat(template("m", "Total ₹{amount}", "Date {received}").usesArrivalDate()).isFalse();
        assertThat(template("m", "Total ₹{amount}", "Order Date: {date}").usesArrivalDate()).isFalse();
    }
}
