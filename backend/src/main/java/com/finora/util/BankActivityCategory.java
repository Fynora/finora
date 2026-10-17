package com.finora.util;

import com.finora.entity.Transaction;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The category of a row the BANK wrote about its own activity -- interest or cashback it paid you,
 * a charge it took, GST on a fee, a card bill payment it received or a card bill paid from the
 * account, a card's own instalment, a recurring-deposit, provident-fund or pension instalment --
 * plus a payment to a government body.
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
 * default category, V246), and government fees are "Taxes". On 2026-10-03 a provident-fund payment
 * was split out of "Taxes": it is savings ("Investments").
 *
 * <p>Runs last, only in place of "Other": the user's rules, learned categories, the keyword table,
 * the shared corpus, the AI cache and the person-transfer rule all still win. A row whose payee is
 * a person is never decided here, and a null direction (a caller with no direction to give)
 * decides nothing.
 */
public final class BankActivityCategory {

    /** The default category for money a bank or card pays you -- see V246. */
    public static final String INTEREST_AND_CASHBACK = "Interest & Cashback";

    private BankActivityCategory() {}

    // Each list is matched as whole words over CategoryRules.normalize(), which lowercases and turns
    // every non-alphanumeric into a space ("Int.Pd:01-05" reads "int pd 01 05").
    // A change here that can move a row off "Other" or "Personal Transfer": raise
    // CategorizationService.SUGGESTION_VERSION so rows already waiting are re-checked.
    private static final Pattern CASHBACK = words("cashback", "cash back");
    // The bank's own interest credit, in each spelling measured. Never a bare "interest": a card's
    // instalment-plan credit reads "... INSTALLMENTS INTEREST" and is not interest the bank paid
    // you. "interest cr": a small finance bank credits interest daily as "Interest Cr. for <date>",
    // which none of the other spellings matched, so every one of those rows was "Other" (2026-10-04).
    // "interest credited", "fd interest", "int credit", "interest payment": spellings no corpus
    // statement prints, added on Sid's decision (2026-10-04) because FlowClassifier already counts a
    // non-card credit carrying them as interest income, so the category and the label disagreed
    // with it. V254 relabelled stored rows with the list as it stood then; V255 queued the rows these
    // four add.
    private static final Pattern INTEREST_EARNED = words(
            "interest paid", "credit interest", "interest credit", "interest credited", "interest cr", "int pd",
            "sb int", "int cr", "int credit", "intcr", "savings interest", "fd interest", "interest payment");
    private static final Pattern CARD_BILL_RECEIVED = words("bbps");
    // "markup fee": a card's foreign-currency markup, printed as one consolidated charge (2026-10-05).
    // "installments interest": a third issuer's wording for an instalment's interest, "... 2ND OF 3
    // INSTALLMENTS INTEREST" (2026-10-14, one row; it was "Other").
    private static final Pattern CHARGED = words(
            "sms charges", "sms charge", "sms chrg", "sms alert", "emi interest", "interest on emi",
            "installments interest", "markup fee");
    /** A card's instalment plan billing each month's share of a converted purchase ("EMI PRINCIPAL",
     *  a FlexiPay "FP EMI"): the card's own loan. Not in CategoryRules, whose words also name
     *  merchants -- there they typed the card a BUSINESS. Measured on the corpus (2026-10-05): one
     *  row each, both "Other". Its interest is a charge, above. */
    private static final Pattern CARD_INSTALMENT = words("emi principal", "fp emi");
    /** A third issuer's wording for the same, "... 2ND OF 3 INSTALLMENTS PRINCIPAL" (2026-10-14, one
     *  row, "Other"). Also "Loan EMI", but kept apart from CARD_INSTALMENT: see isCardInstalment. */
    private static final Pattern CARD_INSTALMENT_NTH_OF_M = words("installments principal");
    private static final Pattern GST = words("gst", "igst", "cgst", "sgst");
    private static final Pattern RECURRING_DEPOSIT = words(
            "rd installment", "rd instalment", "recurring deposit");
    /** The employees' provident fund. Typed GOVERNMENT by CounterpartyClassifier (it is not a
     *  person), but money paid into it is retirement savings that comes back to the payer, not a
     *  tax or a fee. */
    private static final Pattern PROVIDENT_FUND = words("epfo", "epf");
    /** The public provident fund and the government's pension scheme (APY), as the bank prints them
     *  run into an account number or a reference ("...PPF000...", "APY0000..."), where no word
     *  boundary sets the scheme apart. Digits on both sides of "ppf", and after "apy" at a word's
     *  start, keep both out of ordinary words. Measured on the corpus (2026-10-05): one row each. */
    private static final Pattern GLUED_SAVINGS_SCHEME = Pattern.compile("\\b(?:\\d+ppf\\d+|apy\\d{6,})");
    /** A whole UPI id, its name part included: someone's "apy123456@..." is a payee, not the scheme. */
    private static final Pattern UPI_ID = Pattern.compile("[\\p{L}\\p{N}._-]+@[\\p{L}\\p{N}.]+");
    /** A credit card's bill paid from this account: "CC BILLPAY" in a self-transfer or in the card
     *  issuer's own UPI payee, and net banking's "IB BILLPAY" to a biller that carries the card's
     *  masked number ("4000XXXXXX0001"). A bill pay with a consumer number is a utility, not a card;
     *  so is a debit-card bill payment, which prints the debit card's own masked number, hence net
     *  banking's prefix and not any "billpay". Measured on the corpus (2026-10-05): 7 rows, all "Other". */
    // "credclub", "cred club": paid to the card-bill app's own UPI id, which is how its card bills
    // arrive (2026-10-05, 4 rows, uneven bill-sized amounts). A shop paid through the app is paid at
    // the shop's own id and does not carry these words.
    private static final Pattern CARD_BILL_PAID = words("cc billpay", "credclub", "cred club");
    private static final Pattern NET_BANKING_BILL_PAY = words("ib billpay");
    private static final Pattern MASKED_CARD_NUMBER = Pattern.compile("\\b\\d{4,6}[Xx*]{4,8}\\d{4}\\b");

    public static Optional<String> of(String description, Transaction.Type direction) {
        if (description == null || description.isBlank() || direction == null) return Optional.empty();
        // A person is never the bank: a friend's note ("INTEREST PAID", "GST") is their own words
        // about a transfer, not the bank's activity.
        CounterpartyType counterparty = CounterpartyTyping.of(description).type();
        if (counterparty == CounterpartyType.PERSON) return Optional.empty();
        String text = CategoryRules.normalize(description);
        if (direction == Transaction.Type.INCOME) {
            if (namesInterestEarned(text, description) || CASHBACK.matcher(text).find()) {
                return Optional.of(INTEREST_AND_CASHBACK);
            }
            if (CARD_BILL_RECEIVED.matcher(text).find()) return Optional.of("Transfer");
            return Optional.empty();
        }
        // Charges first: "SMS CHARGES+GST" is a fee with its tax, and the fee is what was bought.
        if (CHARGED.matcher(text).find()) return Optional.of("Fees/Interest");
        if (namesCardBillPaid(text, description)) return Optional.of("Transfer");
        if (CARD_INSTALMENT.matcher(text).find() || CARD_INSTALMENT_NTH_OF_M.matcher(text).find()) {
            return Optional.of("Loan EMI");
        }
        if (RECURRING_DEPOSIT.matcher(text).find()) return Optional.of("Investments");
        if (PROVIDENT_FUND.matcher(text).find()) return Optional.of("Investments");
        if (GLUED_SAVINGS_SCHEME.matcher(CategoryRules.normalize(UPI_ID.matcher(description).replaceAll(" "))).find()) {
            return Optional.of("Investments");
        }
        if (GST.matcher(text).find()) return Optional.of("Taxes");
        if (counterparty == CounterpartyType.GOVERNMENT) return Optional.of("Taxes");
        return Optional.empty();
    }

    /**
     * Whether the row is interest the bank credited to you: money in, worded as one of the bank's
     * own interest credits, not a refund or reversal, and not from a person. These are the interest
     * rows {@link #of} files under {@link #INTEREST_AND_CASHBACK}.
     */
    public static boolean isInterestEarned(String description, Transaction.Type direction) {
        if (description == null || description.isBlank() || direction != Transaction.Type.INCOME) return false;
        if (CounterpartyTyping.of(description).type() == CounterpartyType.PERSON) return false;
        return namesInterestEarned(CategoryRules.normalize(description), description);
    }

    /**
     * Whether the row is one month's share of a card instalment plan: money out, worded as the
     * card's own instalment, and not a payment to a person. It reads the word list {@link #of} reads
     * for "Loan EMI" (CARD_INSTALMENT), less one wording below; unlike the category, it still holds
     * after the user picks another category for the row.
     *
     * <p>Read by clients to mark the row. Such a row repeats part of an earlier purchase, and
     * every spending total counts both when the purchase is in the ledger too; nothing here
     * changes what is counted. An instalment's interest and tax are new cost and are not marked.
     *
     * <p>Measured on the real statement corpus (2026-10-12, 34 statements, 1,965 rows): two rows,
     * one on each of two card statements, and none of the 1,682 rows on savings statements.
     * A third issuer prints "... INSTALLMENTS PRINCIPAL" as a debit and, the same day, as an
     * identical credit (measured 2026-10-14: same amount, date, counter and words). Reconciliation
     * already links the credit to the debit by their shared merchant, so that instalment counts
     * nothing, and the mark's note -- that totals count both the purchase and its instalments --
     * would be untrue of it. That wording is "Loan EMI" (CARD_INSTALMENT_NTH_OF_M) but not marked.
     */
    public static boolean isCardInstalment(String description, Transaction.Type direction) {
        if (description == null || description.isBlank() || direction != Transaction.Type.EXPENSE) return false;
        if (CounterpartyTyping.of(description).type() == CounterpartyType.PERSON) return false;
        return CARD_INSTALMENT.matcher(CategoryRules.normalize(description)).find();
    }

    /**
     * Whether a debit's narration pays a credit card's bill: the same test {@link #of} uses to
     * call such a row "Transfer", on its own, so Insights can keep a card bill out of its movers
     * whatever category the row ended up in. A person's own note is never the bank's words.
     */
    public static boolean paysCardBill(String description) {
        if (description == null || description.isBlank()) return false;
        if (CounterpartyTyping.of(description).type() == CounterpartyType.PERSON) return false;
        return namesCardBillPaid(CategoryRules.normalize(description), description);
    }

    private static boolean namesCardBillPaid(String normalized, String description) {
        return CARD_BILL_PAID.matcher(normalized).find()
                || (NET_BANKING_BILL_PAY.matcher(normalized).find() && MASKED_CARD_NUMBER.matcher(description).find());
    }

    /** Whether {@code text} -- a narration, or a field of one -- reads as one of the bank's interest credits. */
    static boolean namesInterest(String text) {
        return text != null && INTEREST_EARNED.matcher(CategoryRules.normalize(text)).find();
    }

    /**
     * An interest phrase, and no refund or reversal word: interest charged and then refunded or
     * reversed ("INTEREST CR REVERSAL") is money coming back, which FlowClassifier already reads
     * ahead of interest -- it is not interest earned.
     */
    private static boolean namesInterestEarned(String normalized, String description) {
        return INTEREST_EARNED.matcher(normalized).find() && !MoneyBackWords.readsAsMoneyBack(description);
    }

    /** Phrases are lowercase letters, digits and single spaces -- the alphabet normalize() leaves. */
    private static Pattern words(String... phrases) {
        return Pattern.compile("\\b(?:" + String.join("|", phrases) + ")\\b");
    }
}
