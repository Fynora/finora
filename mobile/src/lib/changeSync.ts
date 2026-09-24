import {
  QueryClient,
  type InvalidateOptions,
  type InvalidateQueryFilters,
} from '@tanstack/react-query';
import { changesApi, type ChangeStamp } from '../api/endpoints';
import { FINANCIAL_QUERY_KEYS, invalidateFinancialQueries } from './invalidateFinancialData';

/**
 * Keeps the app in step with changes made elsewhere (the web app), and keeps the app's OWN edits
 * from being mistaken for them.
 *
 * The backend answers with one opaque value per kind of data (a "section"). This module remembers
 * the last value it saw for each -- the baseline -- and compares each new answer with it: a section
 * whose value moved was changed by someone else, so the queries that show it are refreshed.
 *
 * The hard part is the app's own edits. An edit moves a section too, and would look like a change
 * from elsewhere and refresh every screen a second time. The tempting fix -- take a fresh reading
 * after an edit and make it the baseline -- has a hole: the reading can already include a change
 * made elsewhere a moment earlier that the refetch of the data has not (yet) picked up, and the
 * baseline would then hide it for good. So a baseline only moves under two rules:
 *
 *   1. The reading is taken BEFORE the refetch it goes with. Anything it contains, the refetch
 *      (which runs after the answer arrives) also contains; anything after it still shows up as a
 *      change at the next poll. GatedQueryClient holds an invalidation back until the reading is in.
 *   2. Only a section whose OWN data is being refetched moves. Invalidating 'accounts' rebaselines
 *      the accounts section and nothing else, so a profile rename made elsewhere at the same moment
 *      still shows as a change. A section with several queries (transactions: the ledger and the
 *      dashboard's recent list) moves only when all of them are refreshed together.
 *
 * If the reading fails or is slow, nothing moves: the next poll reports the app's own edit as a
 * change and refreshes once more. That costs a round of reads, never a missed change.
 */

export const SECTIONS = [
  'transactions',
  'accounts',
  'statementImports',
  'budgets',
  'goals',
  'categories',
  'profile',
  'preferences',
  'billing',
] as const;
export type Section = (typeof SECTIONS)[number];

/** The queries that ARE each section's data, as opposed to figures derived from it. */
const OWN_QUERY_KEYS: Record<Section, readonly string[]> = {
  transactions: ['transactions', 'recent-transactions'],
  accounts: ['accounts'],
  statementImports: ['statement-imports'],
  budgets: ['budgets'],
  goals: ['goals'],
  categories: ['categories'],
  profile: ['user-settings'],
  // A preference (timezone, low-balance threshold) changes what the server CALCULATES, so it is
  // only ours once the profile AND the dashboard's figures are both being re-read.
  preferences: ['user-settings', 'dashboard-summary'],
  billing: ['my-subscription', 'entitlements'],
};

/**
 * Every query the stamp's change handling can refresh: the financial cascade, the profile, the
 * categories and the billing state. All of their invalidations wait for a reading (see the gate),
 * derived figures included -- a baseline moved after a derived query refetched would hide a change
 * from it.
 */
export const COVERED_KEYS: ReadonlySet<string> = new Set<string>([
  ...FINANCIAL_QUERY_KEYS,
  'user-settings',
  'categories',
  'my-subscription',
  'entitlements',
]);

const FINANCIAL_SECTIONS: readonly Section[] = ['transactions', 'accounts', 'statementImports', 'budgets', 'goals'];

/** How long an edit's refresh will wait for its reading before going ahead without rebaselining. */
export const GATE_TIMEOUT_MS = 600;

/** After a reading fails or times out, edits skip the wait for this long: a bad connection should not slow every one. */
export const GATE_SUSPEND_MS = 30_000;

// ---- state ------------------------------------------------------------------------------------

let watching = false;
let baseline: Partial<Record<Section, string>> | undefined;
/** Per section, the request number of the reading that produced its baseline: older ones are ignored. */
let appliedSeq: Partial<Record<Section, number>> = {};
let seqCounter = 0;
let bypassDepth = 0;
let gateSuspendedUntil = 0;

/** Set by useChangePolling while it is enabled. */
export function setChangeWatchActive(value: boolean): void {
  watching = value;
}

export function isChangeWatchActive(): boolean {
  return watching;
}

/** Forgets everything: called when the session ends, so another account is never compared with this one. */
export function resetChangeSync(): void {
  baseline = undefined;
  appliedSeq = {};
  batch = undefined;
}

export function __resetChangeSyncForTests(): void {
  resetChangeSync();
  watching = false;
  seqCounter = 0;
  bypassDepth = 0;
  gateSuspendedUntil = 0;
}

// ---- readings ---------------------------------------------------------------------------------

export type StampReading = { seq: number; stamp: ChangeStamp };

/** Asks the backend. `seq` is taken when the request is SENT, so readings can be put in order. */
export async function fetchStamp(): Promise<StampReading> {
  const seq = ++seqCounter;
  return { seq, stamp: await changesApi.stamp() };
}

/**
 * Compares a poll's answer with the baseline. Returns the sections that moved (none for the first
 * answer, which only establishes the baseline) and makes the answer the new baseline. An answer
 * older than what a section already reflects is ignored for that section.
 */
export function applyPollAnswer(reading: StampReading): Section[] {
  const first = baseline === undefined;
  const known = (baseline ??= {});
  const changed: Section[] = [];
  for (const section of SECTIONS) {
    if (reading.seq < (appliedSeq[section] ?? 0)) continue;
    if (!first && known[section] !== undefined && known[section] !== reading.stamp[section]) changed.push(section);
    known[section] = reading.stamp[section];
    appliedSeq[section] = reading.seq;
  }
  return changed;
}

/** Refreshes what a change to `changed` sections shows. Not gated: this IS the reaction to a change. */
export function refreshChanged(queryClient: QueryClient, changed: readonly Section[]): void {
  withBypass(() => {
    // A category rename shows in every row that carries its name; a preference (timezone) changes
    // what the server calculates for the dashboard and reports. Both refresh those too.
    if (changed.some((s) => s === 'categories' || s === 'preferences' || FINANCIAL_SECTIONS.includes(s))) {
      invalidateFinancialQueries(queryClient, { cancelRefetch: false });
    }
    const keys = new Set<string>();
    if (changed.includes('categories')) keys.add('categories');
    if (changed.includes('profile') || changed.includes('preferences')) keys.add('user-settings');
    if (changed.includes('billing')) ['my-subscription', 'entitlements'].forEach((key) => keys.add(key));
    keys.forEach((key) => void queryClient.invalidateQueries({ queryKey: [key] }, { cancelRefetch: false }));
  });
}

// ---- the gate ---------------------------------------------------------------------------------

/** Runs `fn` with the gate switched off for the invalidations it starts synchronously. */
export function withBypass<T>(fn: () => T): T {
  bypassDepth++;
  try {
    return fn();
  } finally {
    bypassDepth--;
  }
}

type Waiter = { run: () => Promise<void>; resolve: () => void; reject: (error: unknown) => void };
type Batch = { keys: Set<string>; waiters: Waiter[] };
let batch: Batch | undefined;

function startBatch(): Batch {
  const current: Batch = { keys: new Set(), waiters: [] };
  let timer: ReturnType<typeof setTimeout> | undefined;
  const reading = fetchStamp().catch(() => undefined);
  const timeout = new Promise<undefined>((resolve) => {
    timer = setTimeout(() => resolve(undefined), GATE_TIMEOUT_MS);
  });
  void Promise.race([reading, timeout]).then((result) => {
    clearTimeout(timer);
    // Later invalidations start a new batch and take a new reading.
    if (batch === current) batch = undefined;
    if (result && watching) commit(result, current.keys);
    // A failed or slow reading suspends the wait for a while (see GATE_SUSPEND_MS).
    else if (!result) gateSuspendedUntil = Date.now() + GATE_SUSPEND_MS;
    current.waiters.forEach((w) => w.run().then(w.resolve, w.reject));
  });
  return current;
}

/** Moves the baseline of every section whose own queries were ALL refreshed in this batch. */
function commit(reading: StampReading, keys: ReadonlySet<string>): void {
  if (baseline === undefined) return;
  for (const section of SECTIONS) {
    if (!OWN_QUERY_KEYS[section].every((key) => keys.has(key))) continue;
    if (reading.seq < (appliedSeq[section] ?? 0)) continue;
    baseline[section] = reading.stamp[section];
    appliedSeq[section] = reading.seq;
  }
}

/**
 * QueryClient whose invalidations of a section's own data wait for a fresh stamp reading first
 * (see the rules at the top of this file) -- the covered queries, derived ones included. Everything
 * else passes straight through: while nothing
 * is watching the stamp, before there is a baseline, for keys that are not a section's own data,
 * for predicate invalidations (the cold-start restore) and for the poll's own refreshes.
 */
export class GatedQueryClient extends QueryClient {
  override invalidateQueries(filters?: InvalidateQueryFilters, options?: InvalidateOptions): Promise<void> {
    const key = filters?.queryKey?.[0];
    if (
      !watching ||
      bypassDepth > 0 ||
      baseline === undefined ||
      Date.now() < gateSuspendedUntil ||
      filters?.predicate ||
      typeof key !== 'string' ||
      !COVERED_KEYS.has(key)
    ) {
      return super.invalidateQueries(filters, options);
    }
    const current = (batch ??= startBatch());
    current.keys.add(key);
    return new Promise<void>((resolve, reject) => {
      current.waiters.push({
        run: () => super.invalidateQueries(filters, options),
        resolve,
        reject,
      });
    });
  }
}
