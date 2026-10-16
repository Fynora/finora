package com.finora.activation;

/**
 * The fixed answers to "What stopped you?" (Gate 2a spec §4.1), each belonging to one moment, in the
 * order the clients show them. {@link #SKIPPED} belongs to every moment. A new answer needs a change
 * here and in V276's CHECK constraint, never a client-only change.
 */
public enum ActivationAnswer {
    // NO_FILE: "Last time you looked at adding a statement. What stopped you?"
    NO_STATEMENT(ActivationMoment.NO_FILE),
    NOT_COMFORTABLE(ActivationMoment.NO_FILE),
    DIDNT_KNOW_HOW(ActivationMoment.NO_FILE),
    NO_POINT_YET(ActivationMoment.NO_FILE),
    NOT_NOW(ActivationMoment.NO_FILE),

    // REFUSED: "What will you do now?"
    TRY_ANOTHER_FILE(ActivationMoment.REFUSED),
    NO_OTHER_STATEMENT(ActivationMoment.REFUSED),
    DONT_UNDERSTAND_FAILURE(ActivationMoment.REFUSED),
    LEAVE_FOR_NOW(ActivationMoment.REFUSED),

    // NOT_CONFIRMED: "What made you stop?"
    ROWS_LOOKED_WRONG(ActivationMoment.NOT_CONFIRMED),
    WRONG_FILE(ActivationMoment.NOT_CONFIRMED),
    NOT_READY_TO_SAVE(ActivationMoment.NOT_CONFIRMED),
    COME_BACK_LATER(ActivationMoment.NOT_CONFIRMED),

    /** The visible "Skip", or a dashboard card closed. Recorded only so the limits can count it. */
    SKIPPED(null);

    private final ActivationMoment moment;

    ActivationAnswer(ActivationMoment moment) {
        this.moment = moment;
    }

    public boolean allowedFor(ActivationMoment m) {
        return moment == null || moment == m;
    }
}
