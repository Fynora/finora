package com.finora.service;

import java.util.regex.Pattern;

/**
 * Strips structural PII shapes out of a screenshot's OCR'd text before {@link
 * FynScreenshotOcrService} lets it anywhere near a chat turn.
 *
 * <p>Also applied to a transaction narration before {@link MerchantUnderstandingService} sends it
 * to the model for an uncategorised merchant -- the same third-party boundary, the same shapes,
 * through {@link #redactNarration}.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The Fyn architecture (docs/superpowers/specs/2026-09-13-fino-ai-implementation-plan.md, §3/
 * §4.1) is explicit: Claude is sent computed aggregates, <b>never</b> raw transaction narrations,
 * counterparty names, UPI IDs, or full account numbers -- Tiers 2-4 are "none, never exposed to
 * Claude" for every tool in the registry. A user-attached screenshot bypasses that registry
 * entirely: OCR on a bank app, a UPI confirmation, or a statement photo routinely produces exactly
 * that data, and until this class existed it went straight into the chat turn Claude receives
 * unfiltered (found in a security/privacy audit, 2026-09-18).
 *
 * <h2>What this does and does not catch</h2>
 *
 * <p>Deterministic, pattern-based, and <b>deliberately aggressive</b> for the shapes it recognises
 * -- same trade {@link com.finora.observability.SentryScrubber#redactMessage} already makes for
 * crash reports: "the filter has to be right every time" is avoided by over-redacting rather than
 * under-redacting. This closes account numbers, card numbers, UPI transaction reference numbers
 * (UTR/RRN), phone numbers, IFSC codes, and UPI VPAs / email addresses.
 *
 * <p><b>Known limitation, stated rather than silently assumed away:</b> Tier 2 (merchant names)
 * and Tier 3 (transaction narrations) are free text with no reliable structural shape -- catching
 * them would need an entity list or a second model call, either of which is a materially bigger
 * change than this fix. A screenshot showing "SWIGGY" or "grocery run with Priya" still reaches
 * Claude with that text intact. This is a real, narrower gap than the one this class closes, not a
 * claim of full compliance -- unlike this service's previous doc comment, which asserted the
 * never-raw posture was already preserved with nothing in code actually enforcing it.
 *
 * <p>The one amount-shaped exception, spelled out rather than left implicit: a bare INR amount
 * ("4500", no separators) up to 7 digits (under 1 crore) is deliberately left alone -- see {@link
 * #LONG_NUMBER} -- because Fyn cannot answer "how much did I spend on this" if the amount itself
 * is redacted, and that is the single most common reason a user attaches a receipt or payment
 * screenshot in the first place. Every account number, card number, UTR/RRN, and Indian phone
 * number in real use is 8+ digits, so this stays a safe, documented threshold rather than a silent
 * gap.
 *
 * <p>Second known limitation, also stated rather than assumed away: this counts DIGIT characters,
 * so a genuine tesseract misrecognition that splits a real identifier's digit run with a stray
 * letter (a common OCR confusion, e.g. "0" read as "O", "1" as "I"/"l", "5" as "S") could leave
 * both halves under the 8-digit floor and let the original, unmasked identifier through whole.
 * This is a property of doing deterministic redaction on noisy OCR text at all, not something a
 * bigger regex fixes -- a real fix would need fuzzy/OCR-error-tolerant matching, a materially
 * different (and untested) approach this fix does not attempt.
 */
final class FynOcrRedactor {

    private FynOcrRedactor() {}

    // UPI VPA (name@bankhandle, e.g. priya@okhdfcbank) and email addresses share the same shape --
    // both are Tier 4/PII and neither needs a different placeholder for a user reading their own
    // redacted transcript.
    private static final Pattern ID_LIKE = Pattern.compile("[A-Za-z0-9.+_-]{2,}@[A-Za-z][A-Za-z0-9.-]{1,}");

    // 4 letters, a literal '0', 6 alphanumeric -- the fixed NPCI/RBI IFSC shape (e.g. HDFC0001234).   // synthetic-ok: invented placeholder IFSC, not a real branch code
    // Checked before LONG_NUMBER below: an IFSC's digits alone are too short to trip that pattern
    // on their own (the fixed '0' plus a 6-digit tail is only 7 digits, one under LONG_NUMBER's
    // 8-digit floor) -- matching the whole token here first means the letters+digits are redacted
    // as one unit, and is what actually closes the gap a digit-only fallback would miss entirely.
    // CASE_INSENSITIVE: found in this class's own bugs-and-gaps review -- a real IFSC is always
    // printed uppercase, but tesseract does not reliably preserve case on every font/render it
    // OCRs, and an all-lowercase or mixed-case match ("hdfc0001234") would otherwise skip this
    // pattern entirely AND fall short of LONG_NUMBER's digit floor, leaking the whole code.
    private static final Pattern IFSC =
            Pattern.compile("\\b[A-Z]{4}0[A-Z0-9]{6}\\b", Pattern.CASE_INSENSITIVE);

    // 8 or more DIGITS -- not 8 or more characters -- optionally broken up by single spaces/dots/
    // dashes/parens the way a card or account number is often displayed (each repetition consumes
    // exactly one digit plus 0-2 separator characters, so the separators never count toward the
    // threshold). Covers account numbers, card numbers, UPI UTR/RRN references (12 digits), and
    // Indian phone numbers (10, or 12-13 with a country code) in one pattern. Comma is deliberately
    // NOT a recognised separator: Indian currency amounts group with commas ("1,00,000"), never
    // real identifiers, so excluding it is what keeps a lakh/crore-formatted amount readable rather
    // than an accident of this pattern's design. See this class's own doc comment for why 8 is the
    // threshold: comfortably above any of those real identifiers while leaving a typical consumer
    // transaction amount (up to 7 digits, ~1 crore) readable, which is the entire reason Fyn can
    // OCR a receipt or payment screenshot at all.
    //
    // No word-boundary assertion, unlike the already-reviewed phone-number pattern this is modeled
    // on (PiiRedactor.PHONE_PATTERN) -- deliberately dropped rather than copied wholesale, because
    // a masked account number in real bank-app UI is routinely displayed glued to a masking prefix
    // with no separating space at all ("XXXX1234567890"), and a boundary check that requires no   // synthetic-ok: invented placeholder digits, not a real account number
    // preceding word character would let exactly that shape slip through unredacted. This class's
    // whole premise is over-redacting rather than under-redacting (see class doc comment), so this
    // pattern is deliberately looser than a general-purpose PII scrubber would be.
    private static final Pattern LONG_NUMBER = Pattern.compile("\\+?(?:\\d[ .\\-()]{0,2}){7,}\\d");

    /** Null-safe -- returns null for null input, same convention {@code PiiRedactor.redact}
     *  already establishes, so a caller can pipe this straight into further processing without an
     *  extra guard. Order matters: IFSC before the generic long-number sweep (see that pattern's
     *  own doc comment), and both before length truncation happens elsewhere -- truncating a raw,
     *  unredacted string first could cut a PII shape in half at the boundary and let the remaining
     *  half slip through. */
    static String redact(String value) {
        if (value == null) {
            return null;
        }
        String redacted = ID_LIKE.matcher(value).replaceAll("[redacted-id]");
        return redactNumbers(redacted);
    }

    // A bank narration joins its fields with '-', '/' or '|' ("UPI-SHOP NAME-shop@okaxis-REF"), and
    // ID_LIKE allows '-' on both sides of the '@', so on a narration it swallows the neighbouring
    // fields too -- the merchant name included, which is the one thing the model is sent the
    // narration for. Here an id is the field holding the '@', ended by those delimiters or
    // whitespace -- and, leftwards, any lowercase-and-digit chunks the id joins with its own '-'
    // ("first-last@bank", "paytm-12345@ptys", "<phone>-2@ybl"; 49 such ids on the corpus outside
    // the dash layout alone). Only lowercase chunks, starting at a field boundary: UPI ids print in
    // lowercase, a bank's own fields in uppercase, so a dash layout's payee ("SHOP NAME-shop@x")
    // keeps its last word.
    //
    // Either side of the '@' may be empty, but not both: statements cut a long id off right after
    // its '@' (seen on 7 corpus rows), and that leftover local part is
    // the identifying half. A lone '@' ("EMI @ 14.00%") is not an id and stays.
    //
    // The optional lead-in is a statement PDF wrapping a long id onto the next line, so a space
    // sits inside it ("firstpart secondpart@handle") -- the first half is often the payee's own
    // name (10+ corpus rows). Taken only when it is lowercase, as UPI ids print, while the bank's
    // own fields are uppercase, so a merchant code there ("SHOPCODE shop@okaxis") stays; and only
    // when it starts a field, so the tail of an uppercase word ("DEUT2 x@y") is not taken. One
    // pattern rather than a second pass over the placeholder, which keeps redactNarration
    // idempotent: the understanding call redacts again what resolve already redacted.
    private static final Pattern NARRATION_ID = Pattern.compile(
            "(?:(?<![^\\s/|\\-])[a-z0-9._][a-z0-9._\\-]* +)?(?:(?<![^\\s/|\\-])(?:[a-z0-9._*]+-)+)?"
            + "[^\\s/|\\-]+@[^\\s/|\\-]*|@[^\\s/|\\-]+");

    // In a slash layout the UPI id sits right after the 4-letter bank code
    // ("/<payee>/<BANK>/<id>/"), and statements cut it short, sometimes before its '@' --
    // letters only ("/SCBL/<first><last>-1/"), which no shape rule can tell from a word. Its
    // POSITION is the evidence, so the whole slot goes, whatever is left in it: a slot holding a
    // lowercase letter or a digit, as an id does; an uppercase word after "/IMPS/" stays.
    private static final Pattern SLOT_AFTER_BANK_CODE = Pattern.compile(
            "(?<=/[A-Za-z]{4}/)(?=[^/\\s\\[]*[a-z0-9])[^/\\s\\[]+");

    // A UPI id cut off before its '@' leaves a bare lowercase token of letters and digits
    // ("name1234"). Six or more characters, both letters and digits, no uppercase: the shape of an
    // id, not of a word a bank prints. A truncated id of letters alone is not caught.
    private static final Pattern LOWERCASE_ALNUM_ID = Pattern.compile(
            "(?<![A-Za-z0-9._])(?=[a-z0-9._]*\\d)(?=[a-z0-9._]*[a-z])[a-z0-9._]{6,}(?![A-Za-z0-9._])");

    /** {@link #redact} for a transaction narration rather than OCR'd text: the same identifier
     *  shapes, with the id match bounded by the narration's field delimiters. Null-safe and
     *  idempotent. Numbers go before the lowercase-id rule, so a date range glued by letters
     *  ("01-04-2026to30-06-2026") is already two placeholders by then. */
    static String redactNarration(String value) {
        if (value == null) {
            return null;
        }
        // Skipped without an '@': the id pattern is the costliest one here, and finds nothing then.
        String ids = value.indexOf('@') < 0 ? value : NARRATION_ID.matcher(value).replaceAll("[redacted-id]");
        String redacted = redactNumbers(ids);
        redacted = SLOT_AFTER_BANK_CODE.matcher(redacted).replaceAll("[redacted-id]");
        return LOWERCASE_ALNUM_ID.matcher(redacted).replaceAll("[redacted-id]");
    }

    private static String redactNumbers(String value) {
        String redacted = IFSC.matcher(value).replaceAll("[redacted-ifsc]");
        return LONG_NUMBER.matcher(redacted).replaceAll("[redacted-number]");
    }
}
