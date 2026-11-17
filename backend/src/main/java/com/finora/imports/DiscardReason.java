package com.finora.imports;

/**
 * Why the customer discarded a staged statement, when they chose to say (Sid, 2026-11-17). Offered as
 * optional one-tap choices on the discard dialog; skipping is allowed, so a discard without one is
 * normal. Stored on the upload's evidence row (V327) and counted on the admin weekly view.
 */
public enum DiscardReason {
    /** Wrong file. */
    WRONG_FILE,
    /** Covers too many months. */
    TOO_MANY_MONTHS,
    /** Something looks wrong. */
    LOOKS_WRONG,
    /** I'll upload it later. */
    LATER,
    /** Other. */
    OTHER
}
