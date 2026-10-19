package com.finora.activation;

/**
 * The furthest step a signup reached on the way to a first statement (Gate 2a spec §3). The
 * declaration order is the rank: a user counts at the highest step whose fact holds, and the
 * activation page shows the columns in this order.
 */
public enum FunnelStep {
    SIGNED_UP,
    OPENED_IMPORT,
    CHOSE_FILE,
    /** Turned away before reading ({@code statement_analysis_sessions} REJECTED). */
    REFUSED,
    /** A read that failed: a direct upload's FAILED analysis, or a FAILED queued job. */
    READ_FAILED,
    /** A queued import held for review, or any held statement. */
    HELD,
    /** Read successfully, but the review was never opened. */
    READ_NOT_REVIEWED,
    /** The review was opened, but nothing was confirmed. */
    REVIEWED_NOT_CONFIRMED,
    /** A statement was imported, deleted or not. */
    GOT_DATA_IN
}
