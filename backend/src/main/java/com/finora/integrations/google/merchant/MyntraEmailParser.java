package com.finora.integrations.google.merchant;

import com.finora.domain.Money;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase C5.3 — hand-written, not templated. {@code ZomatoEmailParser} doesn't exist; Zomato is
 * C5.3's templated merchant (V86's seeded row, routed through {@link TemplateEmailParser}) because
 * a food-delivery receipt is the same single-total, single-date shape Uber already proved out.
 * Myntra isn't: return, exchange, and refund notifications reuse the same "Order" language a fresh
 * purchase confirmation does, each with an amount and a date of their own that must NOT be staged
 * as a new transaction. Telling those apart needs a negative check before the positive one — a
 * template's one receipt marker has no way to express "and also not this other thing," which is
 * why Myntra stays a class like {@code AmazonEmailParser} and {@code OlaEmailParser} do.
 *
 * <h2>What real confirmation mail says</h2>
 *
 * This parser was first written against invented fixtures that said "Order Confirmed", "Order
 * Total" and "Order Date: August 10, 2026". Real Myntra mail says none of those. A confirmation
 * reads "Your Order Is Confirmed on Mon, 13 Jul" (with "fwd" or "M-Express" between "Your" and
 * "Order" for those services), labels the amount paid "Net Paid" and "Total Amount", and prints no
 * year. The shipped-order update ("We've Shipped Your Order on Wed, 15 Jul") also shows "Total paid",
 * so it is told apart by NOT containing the confirmation wording. The year is recovered from the
 * weekday by {@link WeekdayDayMonthDate}, using the day the email arrived. Both the old wording and
 * the real wording are read.
 */
@Component
public class MyntraEmailParser implements MerchantEmailParser {

    private static final String DOMAIN = "myntra.com";

    /** Same reasoning as every other parser in this package's {@code FIXED_CONFIDENCE}: one path
     *  to {@code PARSED}, so a single fixed value is what this parser currently knows about its
     *  own reliability. */
    private static final double FIXED_CONFIDENCE = 0.9;

    private static final Pattern ORDER_MARKER =
            Pattern.compile("Order Confirmed|(?i:Order Is Confirmed\\s+on)");

    /**
     * Checked before {@link #ORDER_MARKER}: a message that mentions a return/exchange/refund is
     * about undoing or crediting a purchase, not making one, regardless of whether it also quotes
     * the original order confirmation's own text inline (a common template pattern — "Your return
     * for Order Confirmed on 9 Aug is on its way").
     */
    private static final Pattern RETURN_OR_REFUND_MARKER = Pattern.compile(
            "Return Initiated|Refund Processed|Exchange Confirmed", Pattern.CASE_INSENSITIVE);

    /** Label-anchored and digit-run-bounded for the same reason as every sibling parser's total
     *  pattern — see {@code AmazonEmailParser.TOTAL}'s doc comment for the full reasoning, which
     *  applies unchanged here. */
    private static final Pattern NET_PAID = Pattern.compile(
            "Net Paid\\s*:?\\s*(?:₹|Rs\\.?|INR)?\\s*(?<!\\d)([\\d,]{1,18}\\.\\d{2})(?!\\d)",
            Pattern.CASE_INSENSITIVE);

    /** The price breakup's total, which equals "Net Paid" unless a wallet or coupon paid part of it. */
    private static final Pattern TOTAL_AMOUNT = Pattern.compile(
            "Total Amount\\s*:?\\s*(?:₹|Rs\\.?|INR)?\\s*(?<!\\d)([\\d,]{1,18}\\.\\d{2})(?!\\d)",
            Pattern.CASE_INSENSITIVE);

    /** The label the first version of this parser was written against. */
    private static final Pattern TOTAL = Pattern.compile(
            "Order Total\\s*:?\\s*(?:₹|Rs\\.?|INR)?\\s*(?<!\\d)([\\d,]{1,18}\\.\\d{2})(?!\\d)",
            Pattern.CASE_INSENSITIVE);

    /** The date as real confirmations print it, straight after the confirmation wording: no year. */
    private static final Pattern CONFIRMED_ON = Pattern.compile(
            "Order Is Confirmed\\s+on\\s+(.{0,40})", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern DATE_TEXT = Pattern.compile(
            "Order Date:?\\s*([A-Za-z]+ \\d{1,2}, \\d{4}|\\d{4}-\\d{2}-\\d{2}"
                    + "|\\d{1,2} [A-Za-z]+ \\d{4}|\\d{1,2}/\\d{1,2}/\\d{4})");

    @Override
    public boolean canParse(String authenticatedDomain) {
        return DOMAIN.equals(authenticatedDomain);
    }

    @Override
    public ParserResult parse(SanitizedGmailMessage message) {
        String text = message.plainText();

        if (RETURN_OR_REFUND_MARKER.matcher(text).find()) {
            return ParserResult.notAReceipt("return/exchange/refund notification, not a purchase");
        }

        if (!ORDER_MARKER.matcher(text).find()) {
            return ParserResult.notAReceipt("no order confirmation marker found");
        }

        Matcher totalMatch = firstMatch(text, NET_PAID, TOTAL_AMOUNT, TOTAL);
        if (totalMatch == null) {
            return ParserResult.malformed("recognised as an order confirmation but no order total "
                    + "could be extracted -- template may have changed");
        }

        Money amount;
        try {
            amount = Money.of(new BigDecimal(totalMatch.group(1).replace(",", "")));
        } catch (NumberFormatException e) {
            return ParserResult.malformed("order total matched but did not parse as a number: "
                    + totalMatch.group(1));
        }

        LocalDate date = extractDate(text, message.receivedOn());
        if (date == null) {
            return ParserResult.malformed("recognised as an order confirmation but the order date "
                    + "could not be read or resolved to a year -- template may have changed");
        }

        return ParserResult.parsed(new ParsedReceipt(
                message.gmailMessageId(), DOMAIN, null, amount, date, FIXED_CONFIDENCE));
    }

    /** The first pattern, in order, that matches -- the order is the preference. */
    private static Matcher firstMatch(String text, Pattern... patterns) {
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                return matcher;
            }
        }
        return null;
    }

    /**
     * The order date: the labelled date the first version of this parser read, else the year-less
     * weekday date real confirmations print, resolved against the day the email arrived.
     */
    private static LocalDate extractDate(String text, LocalDate receivedOn) {
        Matcher labelled = DATE_TEXT.matcher(text);
        if (labelled.find()) {
            return ReceiptDateFormats.tryParse(labelled.group(1));
        }
        Matcher confirmedOn = CONFIRMED_ON.matcher(text);
        if (confirmedOn.find()) {
            return WeekdayDayMonthDate.resolveAtStart(confirmedOn.group(1), receivedOn).orElse(null);
        }
        return null;
    }
}
