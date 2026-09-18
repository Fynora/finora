package com.finora.service;

import java.util.regex.Pattern;

/**
 * Strips structural PII shapes out of a screenshot's OCR'd text before {@link
 * FynScreenshotOcrService} lets it anywhere near a chat turn.
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
 */
final class FynOcrRedactor {

    private FynOcrRedactor() {}

    // UPI VPA (name@bankhandle, e.g. priya@okhdfcbank) and email addresses share the same shape --
    // both are Tier 4/PII and neither needs a different placeholder for a user reading their own
    // redacted transcript.
    private static final Pattern ID_LIKE = Pattern.compile("[A-Za-z0-9.+_-]{2,}@[A-Za-z][A-Za-z0-9.-]{1,}");

    // 4 letters, a literal '0', 6 alphanumeric -- the fixed NPCI/RBI IFSC shape (e.g. HDFC0001234).   // synthetic-ok: invented placeholder IFSC, not a real branch code
    // Checked before LONG_NUMBER below: an IFSC's digits alone are too short to trip that pattern,
    // but matching the whole token here first means the letters+digits are redacted as one unit
    // rather than the code prefix surviving next to a redacted digit tail.
    private static final Pattern IFSC = Pattern.compile("\\b[A-Z]{4}0[A-Z0-9]{6}\\b");

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
        redacted = IFSC.matcher(redacted).replaceAll("[redacted-ifsc]");
        redacted = LONG_NUMBER.matcher(redacted).replaceAll("[redacted-number]");
        return redacted;
    }
}
