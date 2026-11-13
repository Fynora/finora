package com.finora.observability.jobs;

/** How one run of a background job ended. See {@link BackgroundJobReport#outcomeOf}. */
public enum JobOutcome {
    /** Finished with no failed items. */
    OK,
    /** Finished, but the job reported at least one failed item. */
    PARTLY_FAILED,
    /** An error escaped the job's method. */
    CRASHED,
    /** The job returned early because it is switched off on purpose. */
    OFF
}
