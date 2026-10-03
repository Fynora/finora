package com.finora.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives a stable identity key for the entity on the other side of a transaction.
 *
 * <h2>Why the VPA and not the name</h2>
 *
 * <p>{@code MerchantNormalizationEngine} groups by the first significant token of the description --
 * "a deliberately simple heuristic", by its own class doc, whose misses "are exactly what the manual
 * merge merchants feature exists to fix by hand". That weakness is load-bearing elsewhere: it is the
 * reason the merchant keyword retry has to be gated on an admin-approved lifecycle at all.
 *
 * <p>A UPI VPA is a far stronger key, and the difference is measurable. Keying the real corpus on the
 * VPA local-part concentrated the unresolved value into a median of <b>2 counterparties per
 * statement to explain 80% of it</b>, and counterparties seen three or more times accounted for
 * 79.8% of unresolved value against 14.9% for one-offs. A payee's NAME is truncated differently by
 * every bank and every statement layout; their VPA is not.
 *
 * <h2>What the key is, and what it is not</h2>
 *
 * <p>Keys are prefixed by strength so a caller can tell them apart rather than treating a guess as
 * an identity:
 *
 * <ul>
 *   <li>{@code vpa:<local-part>} -- strong. The handle is dropped deliberately: the same person
 *       collecting on {@code @ybl} and {@code @paytm} with one phone number is one counterparty, and
 *       keeping the handle would split them.</li>
 *   <li>{@code masked:<tail@handle>} and {@code cut:<start>} -- weak: the statement printed only the
 *       end, or only the start, of a UPI id, and strangers' ids can share either.</li>
 *   <li>{@code name:<token>} -- weak, and only as good as the extraction. Two spellings of one payee
 *       will not merge.</li>
 *   <li>{@code ""} -- nothing derivable. Not an error.</li>
 * </ul>
 *
 * <p>This is NOT entity resolution. It over-splits far more often than it over-merges, which is the
 * safe direction to be wrong in: an over-split shows a user two rows to confirm, an over-merge
 * silently attributes one person's money to another. Every concentration figure measured with it is
 * therefore a LOWER bound.
 */
public final class CounterpartyIdentity {

    private CounterpartyIdentity() {}

    /**
     * The "@handle" of a VPA. The local part before it is what identifies the human; the handle is
     * their PSP. The local part is read backwards from here by {@link #idBefore}.
     *
     * <p>The local-part charset deliberately EXCLUDES the hyphen even though a real VPA may contain
     * one. Narrations use "-" as a segment delimiter far more often than a VPA uses it as a
     * character, so allowing it made the read run backwards over the delimiter and swallow the payee
     * name -- "UPI-SUNIL VERMA-sampleuser@ybl" keyed as {@code vpa:verma-sampleuser}, which is worse
     * than useless: it re-introduces exactly the name-truncation instability the VPA key exists to
     * escape. Caught by CounterpartyIdentityTest before this shipped. The one hyphen crossed is a
     * linked-account suffix -- see {@link #idBefore}.
     */
    private static final Pattern AT_HANDLE = Pattern.compile("@([A-Za-z][A-Za-z0-9]{1,})");

    /**
     * The standard UPI narration many banks print: {@code UPI/<DR|CR>/<ref>/<name>/<bank>/<id>/...}.
     * The sixth field is the counterparty's id, but some banks print only its first ~16 characters,
     * so the "@" is often cut off and a line wrap can land inside it. Measured 2026-10-02 on the
     * corpus: PNB and Canara rows in this layout had the id cut before the "@" on 18 rows, every one
     * of which fell to a weak name key -- one friend keyed by name on one row and by id on another.
     */
    private static final Pattern STANDARD_LAYOUT = Pattern.compile("^UPI/(?:DR|CR)/[A-Za-z]?\\d{6,}/[^/]*/[A-Za-z]{2,5}/([^/]*)");

    /** A linked-account suffix ("-1", "-2") a UPI app adds when one person links another bank. */
    private static final Pattern LINKED_ACCOUNT_SUFFIX = Pattern.compile("-[1-9]$");

    /**
     * Rail words, plumbing and reference noise -- present in nearly every narration, identifying in
     * none.
     *
     * <p>MANDATE/DEBIT/BALANCE/CMP were added after measuring the real 29-statement corpus: a
     * recurring SIP mandate debit from the same AMC ("RELIANCE NIPPON LIFE ASSET MANA") produced
     * THREE different keys across its own occurrences purely because the bank appends this
     * boilerplate inconsistently row to row -- "...MANA", "...MANA DEBIT CMP MANDATE DEBIT", and
     * "...MANA Balance DEBIT CMP MANDATE DEBIT" all keyed differently before this fix. These four
     * words are bank-generated mechanism vocabulary, identical to the reasoning that already
     * excludes CR/DR/ACH/NACH/ECS: present on the transaction, never on the payee.
     *
     * <p>Deliberately NOT a general "truncate long names" fix -- that was tried, measured against
     * the same corpus, and rejected: 96% of real name: keys are already under 30 characters (median
     * 15), and the long tail is dominated by REAL long payee names ("SARWESH ENTERPRISES LAWATE
     * PRAVINKUMAR BALASAHEB", "ASIA INSTITUTE OF HAIR TRANSPLANT PVT LTD") that a word-count cap
     * would truncate into a worse, MORE collision-prone key -- the over-merge failure mode this
     * class's own doc says is worse than the status quo. A noise-word addition can only ever narrow
     * a key, never truncate a real name mid-word, so it carries none of that risk.
     */
    private static final Pattern NOISE = Pattern.compile(
            "(?i)^(UPI|NEFT|IMPS|RTGS|TRF|TRANSFER|PAYMENT|PAY|PAID|TO|FROM|BY|REF|RRN|TXN|MB|IB|NB"
            + "|NET|MOB|ONLINE|SELF|OWN|COLLECT|INTENT|CR|DR|ACH|NACH|ECS"
            + "|MANDATE|DEBIT|BALANCE|CMP"
            // Statement furniture printed after the narration, and card-bill boilerplate.
            + "|CHQ|RECEIVED|RECD|BBPS|PMT)$");

    /**
     * Never a payee on their own, but part of real names ("HDFC LIFE", "PAYTM MALL"), so a segment is
     * skipped only when these are ALL it says. The app the money moved through ("Payment from
     * PhonePe_<NAME>" keyed every short-named sender on PhonePe to one key), and the remitter's
     * bank from the fixed bank slot of "UPI/<ref>/CR/<name>/<bank>/..." (the same on every row of
     * that statement). As plain noise words they turned "HDFC LIFE" and "SBI LIFE" into one key.
     */
    private static final java.util.Set<String> NOT_A_PAYEE_ALONE = java.util.Set.of(
            "phonepe", "paytm", "gpay", "bhim",
            "sbi", "sbin", "hdfc", "icic", "icici", "utib", "axis", "punb", "barb", "yesb", "kkbk",
            "cnrb", "ubin", "idib", "ibkl", "cbin", "scbl", "indb", "bank");

    /** The value-date label some banks print after the narration ("Value Dt 01/01/2026"). */
    private static final Pattern VALUE_DATE_LABEL = Pattern.compile("(?i)\\bvalue\\s+dt\\b");

    private static final Pattern SEGMENTS = Pattern.compile("[\\-/_|:]+");
    private static final Pattern PSP_HANDLE = Pattern.compile("@[A-Za-z0-9.]*");
    private static final Pattern NON_LETTERS = Pattern.compile("[^A-Za-z]+");

    /**
     * Hard cap on a returned key, matching {@code transactions.counterparty_key VARCHAR(120)} in
     * V142. Not decoration -- without it this method is bounded by the DESCRIPTION, not by 120.
     *
     * <p>{@code meaningfulPart} concatenates every surviving word of a segment, and {@link
     * #SEGMENTS} only splits on {@code - / _ | :}, so a space-only narration is one segment and its
     * key is the whole narration. Measured against the real shapes: a 123-character IMPS line keys
     * to 118 characters -- two under the column -- and a 500-character description (the width
     * {@code transactions.description} itself accepts) keys to 505. This pipeline deliberately
     * joins wrapped continuation rows into a single narration, so the long end of that range is
     * ordinary input, not a pathological one. Uncapped, the first such row would fail its INSERT,
     * and in {@code ImportService.confirm} that fails the user's entire statement.
     *
     * <p>Truncating rather than returning {@code ""}: two rows carrying the same over-long
     * narration truncate identically, so grouping -- the only thing this key is for -- survives.
     * A key AT the cap is near-certainly a whole narration rather than a name, which is a poor
     * identity but an honest one; {@link #isStrong} already refuses to treat any {@code name:} key
     * as presentable identity, so nothing user-facing can mistake it for a resolved counterparty.
     */
    public static final int MAX_KEY_LENGTH = 120;

    /**
     * A stable key for the counterparty, or {@code ""} when the narration carries nothing usable.
     *
     * <p>A reference-heavy segment is skipped rather than keyed on: a token carrying four or more
     * digits is an RRN or an account fragment, and keying on one would make every transaction its
     * own counterparty -- the exact opposite of what this is for. Only when that leaves nothing are
     * the segments read again word by word, dropping just the reference words.
     */
    public static String keyOf(String description) {
        if (description == null || description.isBlank()) return "";

        String slotKey = standardLayoutKey(description);
        if (slotKey != null) return cap(slotKey);

        Matcher at = AT_HANDLE.matcher(description);
        while (at.find()) {
            IdBeforeAt id = idBefore(description, at.start());
            if (id == null) continue;
            // "**TAIL@HANDLE": the statement printed only the end of the VPA. The tail is shared by
            // strangers, so it is kept with its handle and marked as the weak key it is.
            if (id.masked()) return cap("masked:" + id.local() + "@" + at.group(1).toLowerCase());
            return cap("vpa:" + id.local());
        }

        // Kotak's IMPS debit glues its payee to the reference, so every segment naming the payee
        // carries digits and is skipped, and the key fell to the free-text note ("name:rent").
        Matcher glued = OwnAccountEvidence.GLUED_IMPS_PAYEE.matcher(description);
        if (glued.find()) {
            String payee = meaningfulPart(String.join(" ", NON_LETTERS.split(glued.group(1))).trim());
            if (payee.length() >= 3) return cap("name:" + payee.toLowerCase());
        }

        String best = longestName(description, false);
        // Nothing survived: every segment carried a reference. Try again word by word, so a card
        // line like "UPI SHOPCO 111111111111" still names SHOPCO. Only as a fallback -- run on every
        // row, it re-picked the longest segment on rows that already had a good key.
        if (best.isEmpty()) best = longestName(description, true);
        return best.isEmpty() ? "" : cap("name:" + best.toLowerCase());
    }

    /**
     * @param byWord false: a segment carrying four or more digits is skipped whole (a reference or
     *               account fragment). true: only the words carrying them are dropped.
     */
    private static String longestName(String description, boolean byWord) {
        String best = "";
        for (String segment : SEGMENTS.split(VALUE_DATE_LABEL.matcher(description).replaceAll(" "))) {
            // "@handle" of a VPA too broken for the VPA pattern: the handle names the PSP, and one
            // PSP is shared by every payee on it.
            String trimmed = PSP_HANDLE.matcher(segment).replaceAll(" ").trim();
            if (trimmed.isEmpty()) continue;
            // A UPI intent payment's note in place of a payee ("Pay for Intent"): keyed on, it was
            // "name:for" and joined every such payment.
            if (CategoryRules.INTENT_BOILERPLATE.matcher(trimmed).matches()) continue;
            if (byWord) {
                trimmed = String.join(" ", java.util.Arrays.stream(trimmed.split("\\s+"))
                        .filter(w -> countDigits(w) < 4).toList());
            } else if (countDigits(trimmed) >= 4) {
                continue;
            }
            String letters = String.join(" ", NON_LETTERS.split(trimmed)).trim();
            if (letters.isEmpty()) continue;
            String candidate = meaningfulPart(letters);
            // Two letters is a scrap of a wrapped line ("/Pa"), not a name -- as a key it would join
            // every row that happens to end the same way.
            if (candidate.length() < 3) continue;
            if (java.util.Arrays.stream(candidate.toLowerCase().split(" ")).allMatch(NOT_A_PAYEE_ALONE::contains)) continue;
            if (candidate.length() > best.length()) best = candidate;
        }
        return best;
    }

    /** Applies {@link #MAX_KEY_LENGTH}. Both key shapes go through here: a VPA local part is
     *  {@code [A-Za-z0-9._]{2,}} with no upper bound of its own, so it is no safer than a name. */
    private static String cap(String key) {
        return key.length() <= MAX_KEY_LENGTH ? key : key.substring(0, MAX_KEY_LENGTH);
    }

    /** True when this key came from a VPA, i.e. is safe to treat as an identity rather than a guess. */
    public static boolean isStrong(String key) {
        return key != null && key.startsWith("vpa:");
    }

    /**
     * Words that say how the money moved -- the rail, or a payment gateway that settles to many
     * businesses -- and never who was paid. A name key made only of these is the same key for every
     * payee on that rail.
     *
     * <p>Measured 2026-10-02 on the corpus (classifier v8): of 83 name keys shared by two or more rows
     * of one statement, four were made only of these words, and each joined payments to different
     * payees ("UPI/RRN .../UPIIntent", "Pay via Razorpay", "Pay to BharatPe Merchant", "UPIRET-...").
     * The gateways are the ones {@code PersonToPersonTransferDetector}'s merchant-acquirer marker
     * already names as settling only to onboarded businesses.
     */
    private static final java.util.Set<String> RAIL_AND_GATEWAY_WORDS = java.util.Set.of(
            "upiintent", "upiret", "via", "merchant", "razorpay", "rzp", "bharatpe", "payu", "cashfree");

    /**
     * Bajaj Pay's merchant ids are "bajajpay.&lt;partner&gt;.&lt;merchant&gt;". A bank that cuts the id
     * to its first characters can leave only "bajajpay.&lt;partner digits&gt;", which every shop under
     * that partner shares: on a tester's statement one such key joined an electrician and a
     * shopkeeper. Measured 2026-10-02: no other id in this layout joined two payees on the corpus.
     */
    private static final Pattern PARTNER_PREFIX_ONLY = Pattern.compile("vpa:bajajpay\\.\\d+");

    /**
     * A UPI id a bank printed only the start of, with no "@" to show where it ended. Measured
     * 2026-10-03 on the corpus: one bank prints this id field 15 characters wide and drops the "@"
     * whenever the id runs past it (17 rows, every one exactly 15 characters). Eight of those ids are
     * the start of a longer id printed elsewhere, and one of them begins five different shops' ids
     * under one payment brand. The rows still group by it; it is not taken as one payee.
     */
    static final String CUT_PREFIX = "cut:";

    /**
     * A payment gateway's or UPI app's own id, which settles refunds and reversals for every shop on
     * it; the shop is at most in the free-text remark. Measured 2026-10-03 on the corpus: refunds from
     * a grocery app and a travel site both arrived under {@code pg.razorpay}, and a few users' votes
     * for the first made it the shared suggestion for the second. A shop's OWN id on a gateway
     * ({@code <shop>.rzp}, {@code <shop>.payu}) is not one of these and names that shop.
     */
    private static final java.util.Set<String> GATEWAY_OWN_IDS = java.util.Set.of(
            "vpa:pg.razorpay", "vpa:phonepemerchant");

    /** Google Pay's refund ids ("gpayrefund-online"): the shop that refunded is never printed. */
    private static final String GATEWAY_REFUND_ID_PREFIX = "vpa:gpayrefund";

    /**
     * Whether every row carrying this key was paid to (or by) the same payee, so a choice the user
     * makes for one row can be applied to the others.
     *
     * <ul>
     *   <li>{@code vpa:} -- a full UPI id: yes, unless it is only a payment brand's partner prefix
     *       (see {@link #PARTNER_PREFIX_ONLY}) or a gateway's own id (see {@link #GATEWAY_OWN_IDS}).</li>
     *   <li>{@code masked:} -- only the end of a UPI id was printed, and the end is shared by strangers:
     *       no. Measured on the corpus: one gateway's masked id joined two different shops.</li>
     *   <li>{@code cut:} -- only the start of a UPI id was printed: no (see {@link #CUT_PREFIX}).</li>
     *   <li>{@code name:} -- yes, unless every word is a rail or gateway word (see
     *       {@link #RAIL_AND_GATEWAY_WORDS}), when the payee was never printed at all.</li>
     * </ul>
     */
    public static boolean identifiesOnePayee(String key) {
        if (key == null || key.isBlank()) return false;
        if (key.startsWith("vpa:")) {
            return key.length() > "vpa:".length() && !PARTNER_PREFIX_ONLY.matcher(key).matches()
                    && !GATEWAY_OWN_IDS.contains(key) && !key.startsWith(GATEWAY_REFUND_ID_PREFIX);
        }
        if (!key.startsWith("name:")) return false;
        String[] words = key.substring("name:".length()).trim().split("\\s+");
        return !java.util.Arrays.stream(words).allMatch(w -> w.isEmpty() || RAIL_AND_GATEWAY_WORDS.contains(w));
    }

    private static String meaningfulPart(String letters) {
        StringBuilder sb = new StringBuilder();
        for (String word : letters.split("\\s+")) {
            if (word.length() < 2 || NOISE.matcher(word).matches()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(word);
        }
        return sb.toString().trim();
    }

    /**
     * The counterparty's own UPI id, local part only and lower case, or {@code ""} when the
     * narration shows none. Unlike {@link #keyOf}, the standard layout's id slot counts in capitals
     * too ("airtelprepaidXY"), as long as it is one word: a brand's id is evidence of who was paid
     * even where it is too weak to key on. A capitalised slot with a space in it is a note, not an id.
     */
    public static String payeeHandle(String description) {
        if (description == null || description.isBlank()) return "";
        Matcher m = STANDARD_LAYOUT.matcher(description);
        if (m.find()) {
            String field = m.group(1).trim();
            int at = field.indexOf('@');
            if (at >= 0) field = field.substring(0, at).trim();
            int spaces = field.length() - field.replace(" ", "").length();
            // One space is a line wrap inside an id; more is words. Capitals with a space are a note.
            boolean idShaped = at >= 0 || spaces == 0 || (spaces == 1 && field.equals(field.toLowerCase(Locale.ROOT)));
            if (idShaped && !field.isEmpty() && !field.startsWith("*")) return field.replace(" ", "").toLowerCase(Locale.ROOT);
        }
        String key = keyOf(description);
        return isStrong(key) ? key.substring("vpa:".length()) : "";
    }

    /**
     * The key for the id in the sixth field of the standard layout ({@link #STANDARD_LAYOUT}), or
     * null when the narration is not in that layout or the field does not hold an id.
     *
     * <p>Whatever came after an "@" is dropped (the PSP, possibly cut), one line-wrap space is
     * removed, and a trailing linked-account suffix ("-1", or a lone "-" where the cut fell) goes.
     * A field with no "@" must still look like an id rather than words: more than one space, or
     * capitals with no digit ("Rent June"), and it is left to the name fallback.
     *
     * <p>With the "@" printed, the local part before it is whole: {@code vpa:}. Without it the bank
     * cut the id somewhere, and where is not known: {@code cut:}, which groups the rows that print
     * the same text but names no one ({@link #CUT_PREFIX}).
     */
    static String standardLayoutKey(String description) {
        Matcher m = STANDARD_LAYOUT.matcher(description);
        if (!m.find()) return null;
        String field = m.group(1).trim();
        if (field.isEmpty() || field.startsWith("*")) return null; // masked: read by the "@" path
        boolean hadAt = field.indexOf('@') >= 0;
        if (hadAt) field = field.substring(0, field.indexOf('@')).trim();
        int spaces = field.length() - field.replace(" ", "").length();
        if (spaces > 1) return null;
        String id = field.replace(" ", "");
        id = LINKED_ACCOUNT_SUFFIX.matcher(id).replaceFirst("");
        if (id.endsWith("-")) id = id.substring(0, id.length() - 1);
        if (!id.matches("[A-Za-z0-9._-]{4,}") || id.replaceAll("[._-]", "").isEmpty()) return null;
        // With no "@" to vouch for it, only an id-shaped field counts: ids in this slot print lower
        // case or as digits. Capitals ("Rent June2026") are a note, left to the name fallback.
        if (!hadAt && !id.equals(id.toLowerCase())) return null;
        return (hadAt ? "vpa:" : CUT_PREFIX) + id.toLowerCase();
    }

    record IdBeforeAt(String local, boolean masked) {}

    /**
     * The VPA local part ending right before the "@" at {@code at}, or null when there is none.
     *
     * <p>Two repairs, both measured on the corpus (2026-10-02):
     * <ul>
     *   <li><b>Linked-account suffix.</b> "friend-1@okbank" is the same person as "friend@oksbi":
     *       a UPI app adds the digit for a second bank account. A single 1-9 digit after a hyphen is
     *       dropped and the id before it read instead. 79 rows across nine statements carried one;
     *       without this the id was not read at all and the row fell to a name key.</li>
     *   <li><b>Line wrap inside the id.</b> A bank that wraps the narration can leave a space in the
     *       middle ("SHOPCOMARKETPLAC EPRIVA.PAYU@..."). Only the piece after the space used to be
     *       read, so different payees whose wrapped ids end alike shared a key -- 46 of 65 wrapped
     *       rows were keyed on such a fragment. The piece before the space is joined when it starts
     *       right after a field separator, which a payee's name word does not ("UPI-SUNIL VERMA
     *       sampleuser@ybl" stays {@code sampleuser}). A scrap of three characters or fewer is
     *       joined to the piece before it regardless (F-22: "...ELEMEN TS@HDFCBANK").</li>
     * </ul>
     */
    static IdBeforeAt idBefore(String d, int at) {
        int i = at;
        while (i > 0 && isLocalPartChar(d.charAt(i - 1))) i--;
        String run = d.substring(i, at);
        if (run.length() == 1 && run.charAt(0) >= '1' && run.charAt(0) <= '9'
                && i >= 2 && d.charAt(i - 1) == '-' && isLocalPartChar(d.charAt(i - 2))) {
            int end = i - 1;
            i = end;
            while (i > 0 && isLocalPartChar(d.charAt(i - 1))) i--;
            run = d.substring(i, end);
        }
        while (i >= 2 && d.charAt(i - 1) == ' ' && isLocalPartChar(d.charAt(i - 2))) {
            int j = i - 1;
            while (j > 0 && isLocalPartChar(d.charAt(j - 1))) j--;
            String before = d.substring(j, i - 1);
            // A run of 11+ digits is a transaction reference, not part of an id (a phone number is
            // ten): glued on, it would make every payment its own counterparty. ":" is not a field
            // separator here -- it is the time printed before the id ("03:50:05 ...").
            if (before.length() >= 11 && before.chars().allMatch(Character::isDigit)) break;
            boolean afterSeparator = j == 0 || "/-|".indexOf(d.charAt(j - 1)) >= 0;
            if (!afterSeparator && run.length() > 3) break;
            run = d.substring(j, i - 1) + run;
            i = j;
            if (!afterSeparator) break;
        }
        // In a "/"-separated narration a hyphen inside the field is part of the id
        // ("/goog-payments@axisb", "/gpay-11111111801@okbizaxis"): stopping at it keyed on the tail
        // ("vpa:payments"), which other payees' ids end in too. Only when everything back to the "/"
        // is id characters -- a field holding a name has spaces and is not joined.
        if (i >= 2 && d.charAt(i - 1) == '-') {
            int j = i - 1;
            while (j > 0 && (isLocalPartChar(d.charAt(j - 1)) || d.charAt(j - 1) == '-')) j--;
            if (j > 0 && d.charAt(j - 1) == '/' && j < i - 1) {
                run = d.substring(j, i) + run;
                i = j;
            }
        }
        if (run.length() < 2 || run.replaceAll("[._-]", "").isEmpty()) return null;
        return new IdBeforeAt(run.toLowerCase(), i > 0 && d.charAt(i - 1) == '*');
    }

    /** The characters of a VPA's local part, as {@link #idBefore} reads it. */
    private static boolean isLocalPartChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_';
    }

    private static int countDigits(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) n++;
        }
        return n;
    }
}
