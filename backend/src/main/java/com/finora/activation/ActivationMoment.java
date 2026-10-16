package com.finora.activation;

/** The three moments at which Fynora asks "What stopped you?" (Gate 2a spec §4.1). */
public enum ActivationMoment {
    /** Opened the import screen and never chose a file. Asked on the dashboard, a day later or more. */
    NO_FILE,
    /** A statement was refused or failed to read. Asked on the import screen, beside the reason. */
    REFUSED,
    /** A review was cancelled, or a read expired unconfirmed. */
    NOT_CONFIRMED
}
