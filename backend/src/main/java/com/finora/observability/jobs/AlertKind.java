package com.finora.observability.jobs;

/** The three background-job emails, and the only three (design: "No other emails"). */
public enum AlertKind {
    /** A job just became partly failed, crashed or overdue. */
    BROKEN,
    /** Still broken 24 hours after the BROKEN email; sent once. */
    STILL_BROKEN,
    /** The first clean run after a BROKEN (or STILL_BROKEN) email. */
    WORKING_AGAIN
}
