package com.finora.imports.pdf.trace;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Footer and notice sentences a bank prints on every customer's statement. A blanked copy keeps a
 * printed line verbatim only when its whole text is one of these (see
 * docs/superpowers/specs/2026-10-15-bank-sentences-design.md). A line holding a listed sentence
 * and anything else -- a name, a number -- is not a match, so nothing beside a sentence is ever
 * kept with it.
 *
 * <p>Entries are added by a reviewed PR only, chosen by the owner from his own statements, and
 * each must pass {@link #requireSafe}: no digit, at least four words, no '@', no IFSC-shaped word,
 * ASCII only with no control character, already normalised. An entry can therefore never carry a reference, an account
 * number or a contact.
 */
public final class BankSentences {

    private BankSentences() {}

    /** An IFSC's shape, in lower case since entries are normalised: four letters, a zero, then six
     *  letters or digits, standing apart from other letters and digits. */
    private static final Pattern IFSC_SHAPE = Pattern.compile("(?<![a-z0-9])[a-z]{4}0[a-z0-9]{6}(?![a-z0-9])");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** A control character other than ASCII whitespace. Some fonts print a ligature ("ffi", "fi")
     *  as one: a person reading the statement sees the letters, writes the entry without it, and
     *  a printed line holding it still has to match. */
    static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\t\\n\\x0B\\f\\r]]");

    /** Normalised entries. Grouped by the bank each was seen on, one comment per group. */
    private static final List<String> ENTRIES = List.of(
            // HDFC
            "*closing balance includes funds earmarked for hold and uncleared funds",
            "date narration chq./ref.no. value dt withdrawal amt. deposit amt. closing balance",
            "hdfc bank gstin number details are available at https://www.hdfcbank.com/personal/making-payments/online-tax-payment/goods-and-service-tax.",
            "opening balance dr count cr count debits credits closing bal",
            "this is a computer generated statement and does",
            // INDUSIND
            "(d)for unresolved grievances, please write to: nodal ocer, induslnd bank",
            "(f) credit card closure - to close your credit card, please write to",
            "(g) credit card complaints - to raise any complaint regarding credit card",
            "*gst as applicable on all fees and charges.",
            "a list of drop boxes visit www.indusind.bank.in. you are advised to drop",
            "a) joining & annual fees - joining and annual fee may vary from customer to",
            "all payments are to be made in indian rupees only. the cardholder has the to know your current cred reward points, please log in to your cred",
            "all post-dated cheques. bank of any such event, the statement will be deemed to have been",
            "app from your indusind bank account.",
            "b) cash advance charges - waived. interest accrues on cash advance",
            "benet of only following modes of eecting payment to the bank:",
            "by the due date. please note that making only the minimum payment",
            "c)service charges - late payment charges - outstanding balance: up to",
            "charges or visit www.indusind.bank.in",
            "charges, dcc fee, fuel transaction fee, fee on transport",
            "closest indusind bank atm",
            "communication channels for any enquiries and/or redressal of concerns.",
            "cred indusind bank rupay credit card",
            "credit limit available credit limit cash limit available cash limit",
            "customer and from oer to oer. these shall be communicated to the card",
            "date transaction details merchant category cred points amount (in `)",
            "date transaction merchant cred amount (in `)",
            "every month would result in the repayment stretching over a period of",
            "excluding air mccs & interest calculation. for details, visit",
            "from any bank account. least the minimum amount due before the payment due date.",
            "how to make payments",
            "immediately on such loss.",
            "important messages: promotional messages:",
            "including decimals if any) is not paid on or before the payment due date.",
            "insurance provider: icici lombard general insurance company limited",
            "late payment charges will also be levied in case of no payment or payment",
            "late payment charges will be levied if minimum amount due (exact amount",
            "less than the minimum amount due.",
            "member at the time of applying for the card.",
            "mention your name and mobile number on the reverse of the",
            "not applicable if total amount due is not paid in full.",
            "not deposit post-dated cheque. the bank at its sole discretion may reject of his statement billing date. in case the cardholder does not notify the",
            "note: cred points earned via spending on your cred indusind bank rupay credit card during the current billing cycle are mentioned",
            "of the cardholder. interest is levied if total amount due is not paid o",
            "of the cred points the current statement for the current statement for the current statement",
            "opening balance total cred points for total cred points reversed total cred points transferred",
            "outstanding on the card account.",
            "payments & other credits",
            "payments received against card outstanding will be adjusted against all",
            "period during which there is any transaction/payment or any amount",
            "phone banking for more details.",
            "physical, through the delivery mode as specied by bank for each billing",
            "please click here for details of insurance coverage on your indusind bank",
            "please quote your induslnd bank credit card number in all your correspondence",
            "purchase, in that order. account. the payment details including the default in payment is",
            "purchases & other charges",
            "rtgs/imps/visa money transfer facility.",
            "the authorised modes of payment as mentioned above",
            "through nach facility on your non-induslnd bank account. contact",
            "time with consequent interest payment on your outstanding balance.",
            "timely credit to your card account and avoid levy of penal charges. please",
            "to check available cred points",
            "transactions from the date of the transaction.",
            "transfer from your induslnd bank savings account.",
            "will be calculated as below:",
            "you may write to: principal nodal ocer, indusind bank ltd. (credit cards",
            // HSBC
            "loss/theft/misuse of the card"
    );

    private static final Set<String> LISTED = listed(ENTRIES);

    private static Set<String> listed(List<String> entries) {
        entries.forEach(BankSentences::requireSafe);
        // Set.copyOf refuses a duplicate, so a doubled entry fails at class load.
        return Set.copyOf(entries);
    }

    /** Control characters dropped, lower case, every run of whitespace one space, trimmed. */
    public static String normalise(String line) {
        if (line == null) return "";
        String visible = CONTROL.matcher(line).replaceAll("");
        return WHITESPACE.matcher(visible.strip()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    /** Whether {@code c} is a control character {@link #normalise} drops. */
    public static boolean isControl(char c) {
        return Character.getType(c) == Character.CONTROL
                && c != '\t' && c != '\n' && c != '\u000B' && c != '\f' && c != '\r';
    }

    /** The production list, normalised. */
    public static List<String> entries() {
        return ENTRIES;
    }

    /** The production list as a set, for matching. */
    public static Set<String> listed() {
        return LISTED;
    }

    public static boolean isListed(String normalisedLine) {
        return LISTED.contains(normalisedLine);
    }

    /** A set of synthetic entries for tests, each normalised and checked as a real entry is. */
    static Set<String> forTesting(String... entries) {
        Set<String> out = new LinkedHashSet<>();
        for (String entry : entries) {
            String normalised = normalise(entry);
            requireSafe(normalised);
            out.add(normalised);
        }
        return out;
    }

    /**
     * Refuses an entry that could carry anything identifying. The message names the broken rule,
     * never the entry's text: a refused entry may be exactly the text that must not be printed.
     */
    public static void requireSafe(String entry) {
        if (entry == null) throw new IllegalArgumentException("entry is null");
        if (entry.codePoints().anyMatch(Character::isDigit)) throw new IllegalArgumentException("entry has a digit");
        if (!entry.chars().allMatch(c -> c < 128)) throw new IllegalArgumentException("entry is not ASCII");
        if (CONTROL.matcher(entry).find()) throw new IllegalArgumentException("entry has a control character");
        if (!entry.equals(normalise(entry))) throw new IllegalArgumentException("entry is not normalised");
        if (entry.isEmpty() || entry.split(" ").length < 4) throw new IllegalArgumentException("entry has fewer than 4 words");
        if (entry.indexOf('@') >= 0) throw new IllegalArgumentException("entry has an @");
        if (IFSC_SHAPE.matcher(entry).find()) throw new IllegalArgumentException("entry has an IFSC-shaped word");
    }
}
