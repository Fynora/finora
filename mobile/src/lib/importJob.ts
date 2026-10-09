import type { ImportJobProgress } from '../api/endpoints';

/**
 * Phase 4 (Medium-Tier Parity). Port of frontend/src/lib/importJob.ts -- see that file's own doc
 * comment for why this lives as a pure module rather than a switch inside a component: the
 * lifecycle has nine states and getting "still working" vs. "finished" wrong means a spinner that
 * never stops or a Cancel button on a finished import, neither of which shows up in a render test
 * that only checks a label.
 *
 * Not a full port: `stageLabel` (per-stage-row labels for web's ImportTimeline) supports a surface
 * this app's mobile cut doesn't build -- see ImportProgressCard.tsx's own doc comment for what
 * replaces ImportTimeline here. The helpers at the end of this file (`failureReason`,
 * `listedRecentImports`, `recentImportsRefetchIntervalMs`, `isDismissable`) serve the Statement
 * History screen's "Recent imports" card; everything else is unchanged from the web original.
 */

const IN_FLIGHT: ImportJobProgress['status'][] = [
  'QUEUED', 'PARSING', 'ANALYZING', 'DEDUPING', 'IMPORTING', 'LEARNING',
];

const LABELS: Record<ImportJobProgress['status'], string> = {
  QUEUED: 'Waiting to start',
  PARSING: 'Reading your statement',
  ANALYZING: 'Finding the transactions',
  DEDUPING: 'Checking for duplicates',
  IMPORTING: 'Adding them to your account',
  LEARNING: 'Learning your merchants',
  COMPLETED: 'Ready to review',
  FAILED: "Couldn't finish",
  HELD_FOR_REVIEW: 'Running additional checks',
  HELD_FOR_TRUST_REVIEW: 'Running additional checks',
  CANCELLED: 'Cancelled',
};

export function label(job: ImportJobProgress): string {
  return LABELS[job.status] ?? 'Working';
}

export function isSettled(job: { status: ImportJobProgress['status'] }): boolean {
  return job.status === 'COMPLETED' || job.status === 'FAILED'
    || job.status === 'HELD_FOR_REVIEW' || job.status === 'HELD_FOR_TRUST_REVIEW'
    || job.status === 'CANCELLED';
}

/**
 * Whether a job ended by being handed to a person instead of finishing on its own -- the two
 * triage holds, HELD_FOR_REVIEW (a parser gap) and HELD_FOR_TRUST_REVIEW (the extraction's own
 * evidence didn't add up). Both are terminal per isSettled, but unlike a genuine FAILED or
 * CANCELLED outcome, a held job is not actually over.
 */
export function isHeld(job: { status: ImportJobProgress['status'] }): boolean {
  return job.status === 'HELD_FOR_REVIEW' || job.status === 'HELD_FOR_TRUST_REVIEW';
}

/**
 * Whether the import finished with something to review. A type predicate: the caller needs
 * job.importSessionId as a non-null string to hydrate the review step with, and this is the one
 * place that already knows it's safe.
 */
export function isReviewable(job: ImportJobProgress): job is ImportJobProgress & { importSessionId: string } {
  return job.status === 'COMPLETED' && job.importSessionId !== null;
}

/**
 * Whether to offer Cancel. Mirrors ImportJob.isCancellable() on the server: cancelling stops at
 * IMPORTING, because after that user-visible financial rows exist and removing them is the
 * ledger's job, not the queue's.
 */
export function isCancellable(job: ImportJobProgress): boolean {
  const at = IN_FLIGHT.indexOf(job.status);
  return at >= 0 && at < IN_FLIGHT.indexOf('IMPORTING');
}

/**
 * How far along, 0-100, or null when that cannot honestly be said. Null while rowsTotal is null --
 * the statement has not been counted yet, and a bar sitting at 0% says "nothing is happening" when
 * the truth is "we don't know yet".
 */
export function percent(job: ImportJobProgress): number | null {
  if (job.status === 'COMPLETED') return 100;
  if (job.rowsTotal === null || job.rowsTotal <= 0) return null;
  const done = Math.min(job.rowsProcessed, job.rowsTotal);
  return Math.round((done / job.rowsTotal) * 100);
}

/**
 * The one line of detail under the label, or null when there is nothing honest to add. FAILED
 * returns null here -- this app's mobile ImportProgressCard fetches the job's timeline once on
 * FAILED and shows the reason itself, with the per-code title the timeline's failureCode allows.
 * A list that has no timeline to fetch uses failureReason below instead.
 */
export function detail(job: ImportJobProgress): string | null {
  if (job.status === 'FAILED') return null;
  if (job.status === 'HELD_FOR_REVIEW' || job.status === 'HELD_FOR_TRUST_REVIEW') {
    return "We need to run some additional checks on this statement before we can complete "
      + "the import. We'll notify you once it's ready — no action needed from you right now.";
  }
  if (job.rowsTotal === null) return null;
  if (job.status === 'COMPLETED') {
    return `${job.rowsTotal} ${job.rowsTotal === 1 ? 'transaction' : 'transactions'} found`;
  }
  return `${Math.min(job.rowsProcessed, job.rowsTotal)} of ${job.rowsTotal}`;
}

/** Same words the progress card and the server fall back to, so a job reads alike everywhere. */
export const FAILED_IMPORT_FALLBACK = "Fynora couldn't complete this import. Please try again.";

/**
 * Why a FAILED job failed, for a list with no timeline per row; null for any other status.
 *
 * `job.error` is the server's user-safe reason (ImportJobDto.Progress.failureReason): an admin's
 * resolution message, else the curated message for the failure code, else the fallback -- never the
 * engineer's last_error. The fallback here covers a server that predates that guarantee and still
 * sends null for a failure with no curated message.
 */
export function failureReason(job: ImportJobProgress): string | null {
  if (job.status !== 'FAILED') return null;
  return job.error?.trim() || FAILED_IMPORT_FALLBACK;
}

/**
 * Which recent jobs the Statement History screen lists: everything except COMPLETED, the same cut
 * web's "Recent Imports" makes. A completed job already became a staged session (and, once
 * confirmed, a statement in the list below it); anything else -- still running, held for a check,
 * failed, cancelled -- appears nowhere else once the screen that uploaded it is gone.
 */
export function listedRecentImports(jobs: ImportJobProgress[]): ImportJobProgress[] {
  return jobs.filter((j) => j.status !== 'COMPLETED');
}

/** Keep polling the list while anything in it is still moving -- port of web's helper. */
export function recentImportsRefetchIntervalMs(jobs: { status: ImportJobProgress['status'] }[]): number | false {
  return jobs.some((j) => !isSettled(j)) ? 15_000 : false;
}

/**
 * Whether the owner may dismiss this job from the recent-imports list -- mirrors
 * ImportJob.DISMISSABLE on the server. A running or held import is not over, and hiding it would
 * hide the one place that says so.
 */
export function isDismissable(job: { status: ImportJobProgress['status'] }): boolean {
  return job.status === 'FAILED' || job.status === 'CANCELLED';
}
