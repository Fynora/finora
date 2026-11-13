package com.finora.observability.jobs;

/**
 * What a background job says about its own run, beyond "it returned" or "it threw".
 *
 * <p>Most sweeps catch one item's failure, log it and move on, so a run that failed for 2 of 10
 * accounts returns normally. Those catches call {@link #itemFailed}, and a job that returns early
 * because its switch is off calls {@link #switchedOff}, so the Background jobs page can tell
 * "partly failed" and "off" apart from "fine".
 *
 * <p>Per thread: {@link BackgroundJobObservationHandler} opens a run on the scheduler thread before
 * the job's method and closes it after. A call with no open run (a test, or the same code reached
 * from a request) does nothing, so jobs can call these unconditionally.
 */
public final class BackgroundJobReport {

    /** One run's report. */
    public record Run(int failedItems, Throwable firstError, int handled, boolean switchedOff) {
        static final Run EMPTY = new Run(0, null, 0, false);
    }

    private static final class Accumulator {
        int failedItems;
        Throwable firstError;
        int handled;
        boolean switchedOff;
    }

    private static final ThreadLocal<Accumulator> CURRENT = new ThreadLocal<>();

    private BackgroundJobReport() {}

    /** One item of this run failed and the job carried on. */
    public static void itemFailed(Throwable cause) {
        Accumulator run = CURRENT.get();
        if (run == null) return;
        run.failedItems++;
        if (run.firstError == null) run.firstError = cause;
    }

    /** How many items this run dealt with, for "2 of 10 failed". Adds up across calls. */
    public static void handled(int count) {
        Accumulator run = CURRENT.get();
        if (run == null || count <= 0) return;
        run.handled += count;
    }

    /** This run did nothing because the job is switched off on purpose. */
    public static void switchedOff() {
        Accumulator run = CURRENT.get();
        if (run != null) run.switchedOff = true;
    }

    static void begin() {
        CURRENT.set(new Accumulator());
    }

    /** Closes this thread's run and returns what it reported; an empty run if none was open. */
    static Run end() {
        Accumulator run = CURRENT.get();
        CURRENT.remove();
        if (run == null) return Run.EMPTY;
        return new Run(run.failedItems, run.firstError, run.handled, run.switchedOff);
    }

    /** Crashed beats failed items, which beat off, which beats OK. */
    static JobOutcome outcomeOf(boolean crashed, int failedItems, boolean switchedOff) {
        if (crashed) return JobOutcome.CRASHED;
        if (failedItems > 0) return JobOutcome.PARTLY_FAILED;
        if (switchedOff) return JobOutcome.OFF;
        return JobOutcome.OK;
    }
}
