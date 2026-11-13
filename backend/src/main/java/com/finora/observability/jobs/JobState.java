package com.finora.observability.jobs;

/** A job's state on the Background jobs page: its last outcome, unless it is overdue. */
public enum JobState {
    OK, PARTLY_FAILED, CRASHED, OVERDUE, OFF;

    /** Partly failed, crashed or overdue: the states that need an admin. */
    public boolean needsAttention() {
        return this == PARTLY_FAILED || this == CRASHED || this == OVERDUE;
    }
}
