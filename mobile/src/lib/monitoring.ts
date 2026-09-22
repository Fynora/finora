import * as Sentry from '@sentry/react-native';
import type { Breadcrumb, ErrorEvent } from '@sentry/react-native';
import { isCanceled, isTransportFailure, networkErrorCode } from './apiError';

/**
 * Crash reporting.
 *
 * Disabled unless EXPO_PUBLIC_SENTRY_DSN is set, mirroring how every other external integration in
 * this codebase behaves (RESEND_API_KEY, GOOGLE_APPLICATION_CREDENTIALS): absent config degrades to
 * a no-op rather than crashing or half-working. That also keeps local development and the test
 * suite free of network calls with no extra setup.
 *
 * ---
 *
 * The configuration here is deliberately far more restrictive than Sentry's defaults, because the
 * defaults are wrong for this app specifically.
 *
 * Finora handles bank statements. Its API paths carry account and transaction identifiers, its
 * ledger search sends whatever the user typed (a merchant, a landlord, their own name) as a query
 * parameter, and its registration request body contains an email address, a phone number, and a
 * plaintext password. Sentry's out-of-the-box breadcrumbs capture request URLs including those
 * query strings, and a crash during registration is both a likely event and the one whose context
 * is most sensitive.
 *
 * This repository has already had real customer data reach a source comment (see
 * scripts/check-fixture-hygiene.sh's header). A crash reporter is a second route to the same
 * failure, except the data leaves the building rather than sitting in git.
 *
 * So: no PII, no request bodies, no console breadcrumbs, no session replay, and every URL stripped
 * of its query string and identifiers before it leaves the device.
 *
 * The two scrubbers are exported and tested. Scrubbing that silently stops working looks exactly
 * like scrubbing that works, so it cannot be untested logic buried inside a config object.
 */

/** Replaces UUIDs and long digit runs in a path, so error grouping still works but the specific
 *  account, transaction, or statement isn't identifiable. */
export function redactPath(path: string): string {
  return path
    .replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi, '{id}')
    .replace(/\b\d{4,}\b/g, '{n}');
}

/** Drops the query string entirely -- it carries the ledger's free-text search term -- and
 *  redacts identifiers from what's left. */
export function scrubUrl(raw: unknown): string | undefined {
  if (typeof raw !== 'string') return undefined;
  const [withoutQuery] = raw.split('?');
  return redactPath(withoutQuery);
}

/**
 * Returning null drops the breadcrumb entirely.
 *
 * Console output is the least controlled surface in the app: any `console.log` left in while
 * debugging becomes a breadcrumb, and what people log while debugging a finance bug is amounts and
 * account numbers. Dropped wholesale rather than filtered, because a filter has to be right every
 * time and dropping only has to be right once.
 */
export function scrubBreadcrumb(breadcrumb: Breadcrumb): Breadcrumb | null {
  if (breadcrumb.category === 'console') return null;

  if (breadcrumb.category === 'xhr' || breadcrumb.category === 'fetch') {
    const data = breadcrumb.data ?? {};
    // Method and status are what make a network breadcrumb useful; the body and headers are what
    // make it dangerous. Rebuilt from scratch rather than deleted key by key, so a field Sentry
    // adds in a future version isn't included by default.
    return {
      ...breadcrumb,
      data: {
        method: data.method,
        status_code: data.status_code,
        url: scrubUrl(data.url),
      },
    };
  }

  return breadcrumb;
}

/** Strips request bodies and identity from an outgoing event. */
export function scrubEvent(event: ErrorEvent): ErrorEvent {
  if (event.request) {
    // A registration failure would otherwise ship email, phone, and password in request.data.
    event.request = {
      method: event.request.method,
      url: scrubUrl(event.request.url),
    };
  }

  delete event.user;

  if (event.breadcrumbs) {
    event.breadcrumbs = event.breadcrumbs
      .map(scrubBreadcrumb)
      .filter((b): b is Breadcrumb => b !== null);
  }

  return event;
}

export function initMonitoring(): void {
  const dsn = process.env.EXPO_PUBLIC_SENTRY_DSN;
  if (!dsn) return;

  Sentry.init({
    dsn,

    // Never attach IP address, cookies, or user identity. Sentry's own default is already false,
    // but this is the setting whose accidental flip would be most damaging here, so it's stated
    // rather than inherited.
    sendDefaultPii: false,

    // Distinguishes a TestFlight crash from a store crash without shipping any extra identity.
    environment: __DEV__ ? 'development' : 'production',

    // Crash reporting only. Performance spans are keyed by URL, which would reintroduce the
    // identifiers scrubbed above, and there's no performance question being asked yet that would
    // justify that trade.
    tracesSampleRate: 0,

    // Session replay records the screen. On an app showing balances and transaction history, that
    // is not something to enable by default, or quietly.
    replaysSessionSampleRate: 0,
    replaysOnErrorSampleRate: 0,

    beforeBreadcrumb: scrubBreadcrumb,
    beforeSend: scrubEvent,
  });
}

/**
 * Reports a handled error the user already saw a message for. Use sparingly — an error the app
 * recovered from cleanly usually isn't worth an event.
 *
 * Never pass a caught value's message into `extra`: backend error messages are written for users
 * and can quote the data that failed validation.
 *
 * `details` is attached to the event as a `details` context. Numbers, booleans and fixed labels
 * only -- never a phone number, a code, or anything a user typed.
 */
export function reportHandledError(
  error: unknown,
  context: string,
  details?: Record<string, string | number | boolean | null>
): void {
  if (!process.env.EXPO_PUBLIC_SENTRY_DSN) return;
  Sentry.captureException(error, {
    tags: { context },
    ...(details ? { contexts: { details } } : {}),
  });
}

/**
 * Records that something notable happened which was NOT an error -- for example a fallback path
 * that quietly rescued a user, which would otherwise leave no trace anywhere. Same rules as
 * reportHandledError: no PII, `details` is numbers/booleans/fixed labels only. Use very sparingly.
 */
export function reportHandledEvent(
  message: string,
  context: string,
  details?: Record<string, string | number | boolean | null>
): void {
  if (!process.env.EXPO_PUBLIC_SENTRY_DSN) return;
  Sentry.captureMessage(message, {
    level: 'info',
    tags: { context },
    ...(details ? { contexts: { details } } : {}),
  });
}

/**
 * A timestamp for `reportTransportFailure`'s `startedAtMs`, taken right before a request. Exists
 * only so call sites read `requestStartedAt()` instead of a bare `Date.now()` -- the React
 * Compiler ESLint rule (`react-hooks/purity`) flags a direct `Date.now()` call it cannot prove
 * runs outside render in some (not all -- an incomplete, shallow check) event-handler functions,
 * the same reason this codebase's `newIdempotencyKey()` already wraps `Date.now()` rather than
 * inlining it at each call site.
 */
export function requestStartedAt(): number {
  return Date.now();
}

// At most one transport-failure report per context per this window. An outage doesn't fail one
// request -- it fails every request a screen has in flight, and a person staring at a dead screen
// retries the same action repeatedly. Without this, a single bad minute would file dozens of
// near-identical events instead of one, for no more signal.
const TRANSPORT_FAILURE_REPORT_INTERVAL_MS = 60_000;
const lastTransportFailureReportedAt = new Map<string, number>();

/**
 * Reports a request that never got a response -- offline, DNS failure, connection reset, or the
 * client's own timeout -- with axios's own fixed error code and, when the caller tracked it, how
 * long the request ran before failing. Added 2026-09-22 after a live report of "Can't reach
 * Fynora" on a Play Store build had nothing to check against: no event, no code, no timing, on
 * every screen that shows that message. `context` identifies the call site (e.g.
 * 'auth-entry:identify'); pass `startedAtMs` (a `Date.now()` taken right before the call) to also
 * report how long the request ran, or omit it where that isn't tracked.
 *
 * Does nothing for a deliberate cancel, a request that got a real response (a 4xx/5xx is the
 * server's own problem, already reported through whatever surfaced the message), or a repeat
 * within the window above for the same context.
 */
export function reportTransportFailure(err: unknown, context: string, startedAtMs?: number): void {
  if (isCanceled(err) || !isTransportFailure(err)) return;
  const now = Date.now();
  const last = lastTransportFailureReportedAt.get(context) ?? 0;
  if (now - last < TRANSPORT_FAILURE_REPORT_INTERVAL_MS) return;
  lastTransportFailureReportedAt.set(context, now);
  reportHandledEvent('Request never got a response', 'transport-failure', {
    context,
    code: networkErrorCode(err),
    ...(startedAtMs != null ? { durationMs: now - startedAtMs } : {}),
  });
}

/** Wraps the root component so native crashes and unhandled JS errors are captured. Harmless when
 *  Sentry was never initialized. */
export const withMonitoring = Sentry.wrap;
