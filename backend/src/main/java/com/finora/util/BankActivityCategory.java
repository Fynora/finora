package com.finora.util;

import com.finora.entity.Transaction;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The category of a row the BANK wrote about its own activity -- interest or cashback it paid you,
 * a charge it took, GST on a fee, a card bill payment it received, a recurring-deposit instalment
 * -- plus a payment to a government body.
 *
 * <p>Separate from {@link CategoryRules}' keyword table because these words only mean one thing
 * in one direction: "CASHBACK EARNED" is money the bank paid you, never a purchase, and a "BBPS"
 * debit is a bill being paid while a "BBPS" credit on a card is the card bill being received. The
 * keyword table has no direction, and its Transfer list is also read by reconciliation's transfer
 * pass, so a card-bill phrase added there would change pairing as well as labels.
 *
 * <p>Measured on the real statement corpus (2026-10-02), where every one of these rows was
 * "Other": cashback 18 rows, interest credited 9, SMS alert charges 6, EMI interest 2, GST 5,
 * government fees 7, recurring-deposit instalments 7, card bill payments received 11.
 * Categories decided by Sid on 2026-10-02: money the bank pays you is "Interest & Cashback" (a new
 * default category, V246), and government fees are "Taxes".
 *
 * <p>Runs after the user's rules, learned categories and the keyword table, so all three still
 * win; a null direction (a caller with no direction to give) decides nothing.
 */
public final class BankActivityCategory {

    /** The default category for money a bank or card pays you -- see V246. */
    public static final String INTEREST_AND_CASHBACK = "Interest & Cashback";

    private BankActivityCategory() {}

    // Each list is matched as whole words over CategoryRules.normalize(), which lowercases and turns
    // every non-alphanumeric into a space ("Int.Pd:01-05" reads "int pd 01 05").
    private static final Pattern EARNED = words(
            "cashback", "cash back",
            // The bank's own interest credit, in each spelling measured. Never a bare "interest":
            // a card's instalment-plan credit reads "... INSTALLMENTS INTEREST" and is not interest
            // the bank paid you.
            "interest paid", "credit interest", "interest credit", "int pd", "sb int", "int cr", "intcr",
            "savings interest");
    private static final Pattern CARD_BILL_RECEIVED = words("bbps");
    private static final Pattern CHARGED = words(
            "sms charges", "sms charge", "sms chrg", "sms alert", "emi interest", "interest on emi");
    private static final Pattern GST = words("gst", "igst", "cgst", "sgst");
    private static final Pattern RECURRING_DEPOSIT = words(
            "rd installment", "rd instalment", "recurring deposit");

    public static Optional<String> of(String description, Transaction.Type direction) {
        if (description == null || description.isBlank() || direction == null) return Optional.empty();
        String text = CategoryRules.normalize(description);
        if (direction == Transaction.Type.INCOME) {
            if (EARNED.matcher(text).find()) return Optional.of(INTEREST_AND_CASHBACK);
            if (CARD_BILL_RECEIVED.matcher(text).find()) return Optional.of("Transfer");
            return Optional.empty();
        }
        // Charges first: "SMS CHARGES+GST" is a fee with its tax, and the fee is what was bought.
        if (CHARGED.matcher(text).find()) return Optional.of("Fees/Interest");
        if (RECURRING_DEPOSIT.matcher(text).find()) return Optional.of("Investments");
        if (GST.matcher(text).find()) return Optional.of("Taxes");
        if (CounterpartyTyping.of(description).type() == CounterpartyType.GOVERNMENT) return Optional.of("Taxes");
        return Optional.empty();
    }

    /** Phrases are lowercase letters, digits and single spaces -- the alphabet normalize() leaves. */
    private static Pattern words(String... phrases) {
        return Pattern.compile("\\b(?:" + String.join("|", phrases) + ")\\b");
    }
}
