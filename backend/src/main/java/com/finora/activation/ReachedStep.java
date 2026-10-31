package com.finora.activation;

/**
 * The main path to a first statement, as steps a signup reached. Each is a threshold on
 * {@link FunnelStep}: a signup reached it when their furthest step ranks at or above {@link #from()}.
 * So the counts only fall from one step to the next, and the drop between two neighbours is exactly
 * the people whose furthest step lies between them.
 *
 * <p>REFUSED, READ_FAILED and HELD are not on the path: they rank between a chosen file and a read
 * one, so the people stopped there make up the drop from {@link #CHOSE_FILE} to {@link #FILE_READ}.
 */
public enum ReachedStep {
    SIGNED_UP(FunnelStep.SIGNED_UP, false),
    OPENED_IMPORT(FunnelStep.OPENED_IMPORT, true),
    CHOSE_FILE(FunnelStep.CHOSE_FILE, true),
    FILE_READ(FunnelStep.READ_NOT_REVIEWED, false),
    REVIEW_OPENED(FunnelStep.REVIEWED_NOT_CONFIRMED, true),
    GOT_DATA_IN(FunnelStep.GOT_DATA_IN, false);

    private final FunnelStep from;
    private final boolean fromFunnelRecord;

    ReachedStep(FunnelStep from, boolean fromFunnelRecord) {
        this.from = from;
        this.fromFunnelRecord = fromFunnelRecord;
    }

    /** The lowest furthest step that counts as having reached this one. */
    public FunnelStep from() {
        return from;
    }

    /**
     * Whether reaching this step is known only from the import-funnel record (import_funnel_days),
     * which has rows only from its first day on: someone who signed up before then may have reached
     * it unrecorded.
     */
    public boolean fromFunnelRecord() {
        return fromFunnelRecord;
    }

    public boolean reachedBy(FunnelStep furthest) {
        return furthest.compareTo(from) >= 0;
    }
}
