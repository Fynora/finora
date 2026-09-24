import { telemetryApi } from '../api/client';
import { NAV_TAXONOMY } from '../navigation/taxonomy';

/**
 * Fire-and-forget navigation usage reporting.
 *
 * <p>Three properties matter more than anything else here, and each has a test:
 *
 * 1. The group is derived from the taxonomy rather than passed by the caller, so a second,
 *    drifting copy of the grouping cannot grow at the call sites.
 * 2. A destination outside the taxonomy is dropped, never bucketed as "other" -- the same rule the
 *    backend enforces at ingest.
 * 3. Failure is silent. Measurement must never be able to degrade the thing it measures, so a
 *    failed post never surfaces an error, never retries, and never delays navigation.
 *
 * Nothing identifying is sent: no user, session or device id. That absence is the basis on which
 * this is collected without a consent prompt at all, so it is a constraint to preserve rather than
 * an accident of the current shape.
 */
export type NavEntryPointId = 'group' | 'tab' | 'fab' | 'header' | 'contextual' | 'search';

type QueuedEvent = { destination: string; group: string; entry: NavEntryPointId };

const GROUP_BY_ID = new Map(NAV_TAXONOMY.map((e) => [e.id, e.group]));

const FLUSH_DELAY_MS = 2000;
// Matches NavEventController.MAX_BATCH -- the server caps at the same number, so sending more
// would silently discard the tail rather than record it.
//
// Anything queued beyond this in a single 2s window is dropped rather than carried to the next
// flush. That is deliberate: carrying the remainder means re-arming the timer inside flush(),
// which is a real chance of a double-timer bug in exchange for an overflow that needs 50+
// navigations in two seconds -- not something a person does. Analytics loses a data point in a
// case that should not occur; the app cannot break.
const MAX_BATCH = 50;

let queue: QueuedEvent[] = [];
let timer: ReturnType<typeof setTimeout> | null = null;

async function flush(): Promise<void> {
  if (timer) {
    clearTimeout(timer);
    timer = null;
  }
  if (queue.length === 0) return;
  const events = queue.slice(0, MAX_BATCH);
  queue = [];
  try {
    await telemetryApi.post('/nav-events', { events });
  } catch {
    // Deliberately swallowed and not retried: a dropped usage counter costs a data point, which is
    // nothing a user can see, whereas a retry storm or a surfaced error is.
  }
}

/** Record that a destination was opened, and which affordance carried the user there. */
export function trackNavigation(destination: string, entry: NavEntryPointId): void {
  const group = GROUP_BY_ID.get(destination);
  if (!group) return; // not in the taxonomy: dropped, never bucketed
  queue.push({ destination, group, entry });
  if (!timer) {
    timer = setTimeout(() => {
      void flush();
    }, FLUSH_DELAY_MS);
  }
}

/**
 * A navigation search happened. Never records what was typed -- a count is the entire payload,
 * because docs/engineering/observability.md §3 names the ledger search term as the sharpest case of
 * free text that must never leave the platform.
 */
export function trackNavSearch(): void {
  void telemetryApi.post('/nav-events', { events: [], searches: 1 }).catch(() => {});
}

/** Test-only: drain the queue immediately instead of waiting for the timer. */
export async function __flushNavQueueForTest(): Promise<void> {
  await flush();
}
