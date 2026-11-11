import axios from 'axios';
import NetInfo from '@react-native-community/netinfo';
import * as SecureStore from 'expo-secure-store';
import { safeStorage } from '../lib/safeStorage';
import { reportHandledEvent } from '../lib/monitoring';
import { APP_LOCK_HEADER, APP_LOCK_PIN, APP_VERSION, PLATFORM_HEADER, VERSION_HEADER, clientPlatform } from '../lib/clientIdentity';
import { SUSPENDED_SIGN_OUT_MESSAGE } from '../lib/suspension';
import { AUTH_ACCOUNT_SUSPENDED } from './errorCodes';

// Same fixup as the web app's client.ts: whatever EXPO_PUBLIC_API_BASE_URL is set to always
// resolves to exactly one /api/v1 suffix, whether or not the value already includes it.
export function normalizeApiBase(rawBase: string): string {
  const trimmed = rawBase.replace(/\/+$/, '');
  return trimmed.endsWith('/api/v1') ? trimmed : `${trimmed}/api/v1`;
}

// Expo inlines any env var prefixed EXPO_PUBLIC_ into the JS bundle at build time (mirrors Vite's
// VITE_ convention) -- see mobile/.env.example. Unlike the web app, there's no same-origin
// relative-path fallback: a native app has no dev-server proxy to fall back on, so this must
// always resolve to an absolute origin.
const rawBase = process.env.EXPO_PUBLIC_API_BASE_URL;
if (!rawBase) {
  throw new Error(
    'EXPO_PUBLIC_API_BASE_URL is not set. Copy mobile/.env.example to mobile/.env.local and set it to your backend origin.'
  );
}
const BASE_URL = normalizeApiBase(rawBase);

// No timeout at all was the previous default (axios's own default is 0, i.e. wait forever). On a
// phone that's worse than on the web app this mirrors: a stalled cell connection leaves a request
// hanging with no error, no retry, and nothing in the UI to explain why a screen never loads.
// 30s, not something shorter -- import confirm re-parses the stored statement server-side and a
// large one can genuinely take a while. File uploads (stageCsv/stagePdf) are exempt from this,
// see toUploadProgressConfig in endpoints.ts: they already show upload progress, so an unbounded
// wait there is visible progress rather than a silent hang.
const DEFAULT_TIMEOUT_MS = 30_000;

export const api = axios.create({ baseURL: BASE_URL, timeout: DEFAULT_TIMEOUT_MS });

// Interceptor-free instance for the /auth/refresh call itself, same reasoning as the web app: if
// the refresh call went through `api`'s own response interceptor and also got a 401, it would
// recursively trigger another refresh attempt.
export const rawApi = axios.create({ baseURL: BASE_URL, timeout: DEFAULT_TIMEOUT_MS });

export interface ApiEnvelope<T> {
  success: boolean;
  message: string;
  data: T;
  timestamp: string;
  errorCode: string | null;
  requestId: string | null;
}

// D-23 Phase 2: /auth/google and /auth/apple added alongside login/register -- both mint a
// session before any token exists, same as those two, and both being LISTED here matters for
// more than just the outbound Authorization header (see the 401-handling comment further down):
// a verification failure on either (a stale/expired/wrong-audience credential) must be treated as
// "this sign-in attempt failed", never routed into the refresh-token retry path.
const AUTH_ENDPOINTS_NO_TOKEN = ['/auth/login', '/auth/register', '/auth/refresh', '/auth/forgot-password', '/auth/reset-password', '/auth/reactivate', '/auth/google', '/auth/apple', '/auth/identify', '/auth/otp/email/request', '/auth/otp/email/login', '/auth/otp/phone/login', '/auth/mfa/verify'];

// Matched by describeRefreshFailure to tell "nothing to refresh with" from "the call failed".
const NO_REFRESH_TOKEN_MESSAGE = 'No refresh token stored';

const TOKEN_KEY = 'finora_token';
const REFRESH_TOKEN_KEY = 'finora_refresh_token';

// Bug 42-class fix, ported from frontend/src/api/client.ts (see that file's own comment for the
// full incident this predicate exists to prevent): a plain `.includes(path)` scan matches the
// substring ANYWHERE in the URL, including a query string -- e.g. `/accounts?next=/auth/login`
// would be treated as an auth endpoint, silently withholding the Bearer token or skipping
// 401-retry handling for a request that has nothing to do with auth. Comparing against the END of
// the URL's path (query string stripped) is exact instead. This is a shared PREDICATE, not the
// `.some()` call itself -- both interceptors below still call `AUTH_ENDPOINTS_NO_TOKEN.some(...)`
// directly, so scripts/check-client-auth-policy.py can keep enforcing that both decision points
// actually consult the list.
function pathMatchesAuthEndpoint(url: string | undefined, entry: string): boolean {
  const path = url?.split('?')[0];
  return !!path && (path === entry || path.endsWith(entry));
}

// Request interceptor is async here (the web version's is sync) because SecureStore's stable API
// is Promise-based, unlike localStorage -- axios awaits whatever a request interceptor returns,
// so this needs no other change.
// Shared by `api` and `telemetryApi` (below) rather than duplicated, so a telemetry request
// carries exactly the same Authorization and client-identity headers a normal one does -- the
// platform header in particular, which is what the backend resolves a nav event's `platform` tag
// from. A second hand-written copy would drift and silently mis-tag one of them.
async function attachClientHeaders(config: any) {
  // Which signed-in session sent this. A 401 that comes back after that session has ended must not
  // start a refresh -- see markSessionEnded.
  config._sessionEpoch = sessionEpoch;
  const isAuthEndpoint = AUTH_ENDPOINTS_NO_TOKEN.some((path) => pathMatchesAuthEndpoint(config.url, path));
  // An Authorization the caller set itself wins: sign-out's clean-up sends the departing session's
  // token explicitly, after storage has already been cleared (see AuthContext's logout()).
  if (!isAuthEndpoint && !config.headers.Authorization) {
    const token = await safeStorage.getItem(TOKEN_KEY);
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
      // Sent under the app's own signed-in session, not a token the caller supplied (the suspended
      // screen's, sign-out's clean-up). Only such a request may end the session on AUTH_018.
      config._sentWithSession = true;
    }
  }
  // Which app and which build, for support tickets and feedback to record. Sent on every request
  // rather than only the endpoints that store it, so the contract is one rule instead of a list
  // that drifts -- the same shape the web client uses. See lib/clientIdentity.ts.
  config.headers[PLATFORM_HEADER] = clientPlatform();
  config.headers[VERSION_HEADER] = APP_VERSION;
  config.headers[APP_LOCK_HEADER] = APP_LOCK_PIN;
  return config;
}

api.interceptors.request.use(attachClientHeaders);

/**
 * For fire-and-forget telemetry ONLY. Authenticated like `api`, but with **no response
 * interceptor** — and that is the entire point.
 *
 * `api`'s response interceptor turns any 401 on a non-auth endpoint into a token refresh, and a
 * failed refresh into a full sign-out through the registered callbacks below.
 *
 * A usage counter must never be able to do that. Sending nav events through `api` meant a
 * best-effort analytics POST could sign someone out mid-tap, at a moment when nothing they were
 * actually doing required the network, and added a refresh attempt to every navigation.
 *
 * A telemetry 401 is simply dropped. If the session really is gone, the user's next real request
 * finds out through the path built to handle it.
 */
export const telemetryApi = axios.create({ baseURL: BASE_URL, timeout: DEFAULT_TIMEOUT_MS });
telemetryApi.interceptors.request.use(attachClientHeaders);

// The web version's clearSessionAndRedirect() called `window.location.href = '/login'` directly
// -- there's no window/location on native, and this module has no business importing a navigation
// library. Instead it exposes two callbacks the app registers once at startup (see AuthContext),
// keeping the API client itself free of any navigation dependency.
type SessionCallback = () => void;
/** `endedDeliberately`: the server ended this session on purpose (signed out from another device,
 *  "sign out other devices", a security sign-out, or the account deactivated) rather than it timing
 *  out. AuthContext stops this phone's notifications only in that case.
 *  `message`: why, in words for the sign-in screen, when there is something to say (today only the
 *  account being suspended mid-session). */
export type SessionExpiredCallback = (info: { endedDeliberately: boolean; message?: string }) => void;
let onSessionExpired: SessionExpiredCallback = () => {};
let onPhoneVerificationRequired: SessionCallback = () => {};

export function setSessionCallbacks(handlers: {
  onSessionExpired?: SessionExpiredCallback;
  onPhoneVerificationRequired?: SessionCallback;
}) {
  if (handlers.onSessionExpired) onSessionExpired = handlers.onSessionExpired;
  if (handlers.onPhoneVerificationRequired) onPhoneVerificationRequired = handlers.onPhoneVerificationRequired;
}

/**
 * Why a session refresh failed, in numbers and fixed labels only (never a message or a token).
 * The refresh error used to be dropped on the floor, so a tester bounced to the sign-in screen with
 * FYNORA-MOBILE-7 (a 401 on the verify screen) left nothing to say whether the backend had judged
 * the session idle, revoked it as a reuse-of-a-rotated-token precaution, or the refresh call simply
 * never reached it (no network, or no stored token) -- three very different problems.
 */
export function describeRefreshFailure(err: unknown): { refreshHttpStatus: number | null; refreshFailureReason: string } {
  const response = (err as { response?: { status?: unknown; data?: { errorCode?: unknown } } } | null)?.response;
  if (!response) {
    // Two different situations with no HTTP response: there was no refresh token to present (thrown
    // by refreshAccessToken itself, so its exact message is ours to match), or the call never got an
    // answer (offline, timeout). The first is routine after a sign-out; the second is worth knowing.
    const noToken = err instanceof Error && err.message === NO_REFRESH_TOKEN_MESSAGE;
    return { refreshHttpStatus: null, refreshFailureReason: noToken ? 'no-stored-token' : 'no-response' };
  }
  return {
    refreshHttpStatus: typeof response.status === 'number' ? response.status : null,
    refreshFailureReason: refreshReasonLabel(response.data?.errorCode),
  };
}

/**
 * Whether a rejected refresh means the session was ended on purpose rather than timing out:
 * AUTH_004 (revoked -- "sign out this device" from another device, "sign out other devices", or the
 * reuse-detection sign-out) and AUTH_007 (account deactivated). Idle (AUTH_005), max-age (AUTH_006)
 * and an expired token (AUTH_002) are time running out, and keep the phone's notifications --
 * product decision, 2026-10-05.
 */
function endedDeliberately(err: unknown): boolean {
  const code = refreshErrorCode(err);
  return code === 'AUTH_004' || code === 'AUTH_007';
}

/** The backend's error code on a rejected refresh, or undefined. */
function refreshErrorCode(err: unknown): unknown {
  return (err as { response?: { data?: { errorCode?: unknown } } } | null)?.response?.data?.errorCode;
}

// The backend's refresh rejections, as plain words. The first production report of a refresh
// failure arrived with the backend's own error code replaced by "[Filtered]" in Sentry, which left
// the one fact the report existed to carry unreadable. Sending a word rather than the raw code, under
// a name that says what it is, is meant to avoid whatever rule hid it. It has not been confirmed
// which rule that was, so the next real event is what proves this works.
const REFRESH_REASON_LABELS: Record<string, string> = {
  AUTH_002: 'token-unknown-or-expired',
  AUTH_004: 'session-revoked',
  AUTH_005: 'idle-timeout',
  AUTH_006: 'max-age',
  AUTH_007: 'account-deactivated',
  AUTH_018: 'account-suspended',
};

function refreshReasonLabel(errorCode: unknown): string {
  if (typeof errorCode !== 'string' || errorCode === '') return 'none';
  const known = REFRESH_REASON_LABELS[errorCode];
  if (known) return known;
  // A code the app does not know yet still has to be readable, so it is passed on in a bounded,
  // lower-case form rather than dropped.
  return `unmapped-${errorCode.toLowerCase().replace(/[^a-z0-9]+/g, '-').slice(0, 24)}`;
}

// At most one report per this many ms. A sign-out with requests still in flight sends each of them
// down the failed-refresh path in the same instant, and every routine idle-expiry ends this way too;
// one event per burst says what happened, N of them would just spend Sentry quota.
const REFRESH_FAILURE_REPORT_INTERVAL_MS = 60_000;
let lastRefreshFailureReportedAt = 0;

/**
 * Counts signed-in sessions on this device; bumped the moment one ends (sign-out or a rejected
 * refresh). Every request records the value it was sent under, and every refresh the value it
 * started under.
 *
 * Added after a phone was signed straight back in (2026-10-05): a refresh started by background
 * requests was still in flight when the user signed out, finished a moment later, and wrote a fresh
 * token pair to storage. The app was closed before sign-out's own cleanup ran, so the next launch
 * restored that session. A refresh or a 401 that belongs to an ended session now does nothing:
 * nothing is persisted and nothing is cleared (a new session may already be signed in). The pair a
 * late refresh was handed goes to whoever ended the session -- see markSessionEnded.
 */
let sessionEpoch = 0;

/** From markSessionEnded until the ending session's tokens are out of storage: no refresh may start
 *  and read them. Released by sessionStorageCleared. */
let refreshBlocked = false;

export type RefreshedPair = { token: string; refreshToken: string };

/** Thrown inside the refresh path when the session it belonged to ended while it was running.
 *  Carries the pair the server issued, if it got that far, so it is never left unaccounted for. */
class SessionEndedError extends Error {
  constructor(readonly orphan: RefreshedPair | null = null) {
    super('The session ended while its refresh was in flight');
  }
}

/**
 * Ends the current session for everything already in flight. Call it first in any sign-out, before
 * storage is touched, and call sessionStorageCleared once the tokens are removed.
 *
 * Detaches a running refresh, so a request from the next session starts its own rather than joining
 * one that will be discarded. Returns the pair that refresh ends up with (null if none was running,
 * or it failed): the caller must use it for the server-side sign-out. Ending that pair anywhere
 * else would race the caller's own clean-up -- the backend ends the whole session on logout, and a
 * rotated token presented after that is treated as theft, which ends every session the user has.
 */
export function markSessionEnded(): Promise<RefreshedPair | null> {
  sessionEpoch += 1;
  refreshBlocked = true;
  // The next session must not join a renewal (and its retry) that belonged to this one.
  renewInFlight = null;
  const inFlight = refreshInFlight;
  refreshInFlight = null;
  if (!inFlight) return Promise.resolve(null);
  return inFlight.then(
    (pair) => pair,
    (error) => (error instanceof SessionEndedError ? error.orphan : null)
  );
}

/** The ending session's tokens are out of storage; refreshes may run again (for the next session). */
export function sessionStorageCleared(): void {
  refreshBlocked = false;
}

// Mirrors every key AuthContext.logout() clears on the web app.
async function clearSessionAndRedirect(endedDeliberately = false, message?: string) {
  // A refresh failing is what brings us here, so none is left in flight to hand anything over.
  void markSessionEnded();
  try {
    await Promise.all([
      safeStorage.removeItem(TOKEN_KEY),
      safeStorage.removeItem(REFRESH_TOKEN_KEY),
      safeStorage.removeItem('finora_email'),
      safeStorage.removeItem('finora_name'),
      safeStorage.removeItem('finora_phone_verified'),
    ]);
  } finally {
    sessionStorageCleared();
  }
  onSessionExpired(message ? { endedDeliberately, message } : { endedDeliberately });
}

// Every backend response arrives wrapped in a standard envelope:
// { success, message, data, timestamp, errorCode, requestId }. This interceptor transparently
// unwraps it, same as the web app.
function unwrapEnvelope(response: any) {
  if (response.data && typeof response.data === 'object' && 'success' in response.data) {
    response.data = response.data.data;
  }
  return response;
}

// Refresh tokens rotate server-side on every use (RefreshTokenService.rotate()) -- presenting an
// already-rotated token is treated as a theft signal and revokes every active session for the
// user. This shared in-flight promise (same pattern as the web app) means N requests that 401
// around the same moment all await the SAME refresh call instead of each independently racing to
// present the same soon-to-be-stale refresh token.
let refreshInFlight: Promise<RefreshedPair> | null = null;

/**
 * Reads the stored token, rotates it, and persists the new pair -- all inside the shared promise.
 *
 * The persistence belongs in here, not in the caller, and that is the whole point. When the write
 * lived in the interceptor, `.finally` cleared this guard the instant the network call settled,
 * while storage still held the OLD token for the length of two further awaits. A 401 arriving in
 * that window found the guard open, read the retired token, and presented it again --
 * RefreshTokenService.rotate() reads a second presentation as theft and revokes every session the
 * user has, on every device. So one badly-timed request signs you out everywhere.
 *
 * Reproduced deterministically in refreshRace.test.ts before this was changed: the fake server
 * recorded ["R1", "R1"]. Holding the guard until the new pair is written means a late caller either
 * joins this promise or reads storage that is already current -- there is no ordering left in which
 * a retired token can be sent.
 *
 * Reading the token in here rather than accepting it as an argument closes the same window from the
 * other side: a caller that read storage before joining would otherwise hand in a value that was
 * already stale by the time it was used.
 *
 * The two persistence writes below use SecureStore directly, NOT safeStorage -- safeStorage's
 * setItem deliberately never throws (a failed write is a silent no-op, the right contract for an
 * ordinary storage call). That contract is wrong here: the server has already rotated the refresh
 * token by the time these writes run, so if the access-token write lands but the refresh-token
 * write then fails (a transient keychain/Keystore error), a swallowed failure would leave storage
 * holding a new access token paired with the OLD, already-invalidated refresh token -- invisible
 * until the next ordinary refresh presents that stale token again, which the server reads as theft
 * and revokes every session for the user on every device. Letting the write throw here routes that
 * failure into the same catch below as a rejected refresh: clearSessionAndRedirect() wipes both
 * keys, so no mismatched pair is left behind for a later request to find.
 */
function refreshAccessToken(): Promise<RefreshedPair> {
  // Signing out: the ending session's tokens may still be in storage for a moment, and a refresh
  // started now would read them and rotate the session it is ending.
  if (refreshBlocked) return Promise.reject(new SessionEndedError());
  if (!refreshInFlight) {
    const epoch = sessionEpoch;
    const attempt = (async () => {
      const stored = await safeStorage.getItem(REFRESH_TOKEN_KEY);
      if (!stored) throw new Error(NO_REFRESH_TOKEN_MESSAGE);
      const { authApi } = await import('./endpoints');
      const refreshed = await authApi.refresh(stored);
      // Signed out while the call was out: never persist the pair. It goes to the sign-out's own
      // clean-up instead (markSessionEnded), which revokes push with it and ends the session.
      if (epoch !== sessionEpoch) throw new SessionEndedError(refreshed);
      await SecureStore.setItemAsync(TOKEN_KEY, refreshed.token);
      await SecureStore.setItemAsync(REFRESH_TOKEN_KEY, refreshed.refreshToken);
      // Signed out between the two checks, i.e. while the writes above were landing. Take back only
      // what this refresh wrote: a session signed in since then owns whatever else is there.
      if (epoch !== sessionEpoch) {
        await Promise.all([
          removeIfStill(TOKEN_KEY, refreshed.token),
          removeIfStill(REFRESH_TOKEN_KEY, refreshed.refreshToken),
        ]);
        throw new SessionEndedError(refreshed);
      }
      return refreshed;
    })();
    refreshInFlight = attempt;
    // Only clears the guard if it still points at this attempt -- markSessionEnded may already have
    // detached it, and a newer refresh may have taken its place.
    attempt
      .finally(() => {
        if (refreshInFlight === attempt) refreshInFlight = null;
      })
      .catch(() => {});
  }
  return refreshInFlight;
}

/**
 * What a failed refresh means for the session.
 * - 'ended': the session this refresh belonged to ended while it ran (SessionEndedError).
 * - 'refused': the server answered no (expired, revoked, suspended...), or there was no stored
 *   token to present. The session is over.
 * - 'not-sent': the request cannot have changed anything on the server: the phone has no internet,
 *   or the rate limiter (a filter, ahead of the token code) answered 429. The stored token is still
 *   the current one, so the session is kept.
 * - 'unknown': no clear answer while online -- a timeout, a dropped connection, a 408 or a 5xx.
 *   The server may have rotated the token before the answer was lost.
 */
type RefreshFailure = 'ended' | 'refused' | 'not-sent' | 'unknown';

function classifyRefreshFailure(err: unknown): RefreshFailure {
  if (err instanceof SessionEndedError) return 'ended';
  if (err instanceof Error && err.message === NO_REFRESH_TOKEN_MESSAGE) return 'refused';
  // Only a failure of the HTTP call itself can be a lost answer. Anything else -- a SecureStore
  // write failing after the server already rotated the pair -- ends the session as it always did:
  // retrying would present the rotated-away token again, which the server reads as theft.
  const fromHttp = axios.isAxiosError(err) || (typeof err === 'object' && err !== null && 'config' in err);
  if (!fromHttp) return 'refused';
  const status = (err as { response?: { status?: unknown } } | null)?.response?.status;
  if (status === 429) return 'not-sent';
  if (typeof status === 'number' && status >= 400 && status < 500 && status !== 408) return 'refused';
  return 'unknown';
}

/** NetInfo's view: no connection, or a connection with no internet behind it (a captive portal). */
async function phoneHasNoInternet(): Promise<boolean> {
  try {
    const state = await NetInfo.fetch();
    return state.isConnected === false || state.isInternetReachable === false;
  } catch {
    return false;
  }
}

// Well inside the server's 30-second refresh-reuse window (app.jwt.refresh-reuse-grace-ms): if the
// first attempt did rotate the token and only its answer was lost, the retry presents the same token
// within the window and is answered with a fresh pair for the same session.
let renewRetryDelayMs = 2000;

/** Test-only: the retry delay, so tests do not wait two real seconds. */
export function __setRenewRetryDelayForTests(ms: number): void {
  renewRetryDelayMs = ms;
}

type RenewOutcome =
  | { kind: 'renewed'; pair: RefreshedPair }
  | { kind: 'ended' }
  | { kind: 'kept'; error: unknown }
  | { kind: 'signed-out'; error: unknown };

let renewInFlight: Promise<RenewOutcome> | null = null;

/**
 * A refresh that does not sign anyone out over a bad network. It used to: every refresh failure,
 * including no signal at all, ended the session -- one source of phones landing on the sign-in
 * screen early. Now:
 * - refused by the server: signed out, as before;
 * - never reached the server: kept;
 * - no clear answer while online: one retry after a short pause. A clear answer settles it. Still
 *   nothing: signed out on this phone, as before -- keeping a token the server may already have
 *   rotated would replay it after the reuse window, which reads as theft and signs out EVERY device.
 *
 * Shared by every caller waiting at the same moment, like refreshAccessToken itself. The caller
 * clears the session for 'signed-out'; this only decides.
 */
function renewSession(): Promise<RenewOutcome> {
  if (renewInFlight) return renewInFlight;
  const attempt = (async (): Promise<RenewOutcome> => {
    for (let tries = 1; ; tries++) {
      try {
        return { kind: 'renewed', pair: await refreshAccessToken() };
      } catch (err) {
        const failure = classifyRefreshFailure(err);
        if (failure === 'ended') return { kind: 'ended' };
        if (failure === 'refused') return { kind: 'signed-out', error: err };
        if (failure === 'not-sent' || (await phoneHasNoInternet())) return { kind: 'kept', error: err };
        if (tries >= 2) return { kind: 'signed-out', error: err };
        await new Promise((resolve) => setTimeout(resolve, renewRetryDelayMs));
      }
    }
  })();
  renewInFlight = attempt;
  void attempt.finally(() => {
    if (renewInFlight === attempt) renewInFlight = null;
  });
  return attempt;
}

/** Ends the session after a refresh that settled on 'signed-out', with the suspended reason when
 *  that is why (#2513). */
function signOutAfterRefusedRefresh(err: unknown): Promise<void> {
  const suspended = refreshErrorCode(err) === AUTH_ACCOUNT_SUSPENDED;
  return clearSessionAndRedirect(endedDeliberately(err), suspended ? SUSPENDED_SIGN_OUT_MESSAGE : undefined);
}

/**
 * One refresh, to learn whether the saved session is still alive before the app asks for the PIN
 * or Face ID on a cold start. The bootstrap restores a saved session without asking the server, so
 * without this a session the server had already ended was unlocked first and only then sent to the
 * sign-in screen (design 2026-11-10, section 4).
 *
 * - 'alive': the server rotated the pair (stored, exactly as any refresh does).
 * - 'ended': the server refused it (the session is cleared), or there was nothing to present.
 * - 'offline': the phone could not reach the server. Nothing is cleared -- the app works on cached
 *   data offline, and a session that really has ended is caught by the next refresh.
 * See renewSession for how an unclear answer is settled.
 */
export async function verifySession(): Promise<'alive' | 'ended' | 'offline'> {
  const outcome = await renewSession();
  if (outcome.kind === 'renewed') return 'alive';
  if (outcome.kind === 'ended') return 'ended';
  if (outcome.kind === 'kept') return 'offline';
  await signOutAfterRefusedRefresh(outcome.error);
  return 'ended';
}

async function removeIfStill(key: string, value: string) {
  if ((await safeStorage.getItem(key)) === value) await safeStorage.removeItem(key);
}

api.interceptors.response.use(
  (response) => unwrapEnvelope(response),
  async (error) => {
    const originalRequest = error.config;

    // A 401 on anything other than an auth endpoint: try exactly once to refresh the access token
    // and replay the original request.
    //
    // Bug fix: this used to exclude only '/auth/refresh', not the whole AUTH_ENDPOINTS_NO_TOKEN
    // list the request interceptor above already uses -- the web app's client.ts carries this
    // exact fix and its reasoning, and the port to mobile didn't bring it across. A 401 from
    // /auth/login means "wrong password", not "your session expired", but this branch treated the
    // two identically. For a signed-out user with a stale refresh token still in SecureStore (an
    // app killed mid-logout, or a logout whose network call failed), one mistyped password sent
    // that stale token to /auth/refresh -- and presenting an already-rotated refresh token is
    // exactly what RefreshTokenService.rotate() treats as a theft signal, revoking every active
    // session for that user on every device. A typo on the sign-in screen could sign you out
    // everywhere.
    const isAuthEndpoint = AUTH_ENDPOINTS_NO_TOKEN.some((path) => pathMatchesAuthEndpoint(originalRequest.url, path));

    // A request sent under a session that has since ended (signed out while it was in flight), or
    // one that carries its own credentials on purpose (sign-out's clean-up), has nothing to refresh:
    // its 401 is simply that request's failure.
    // (Every request gets an epoch from attachClientHeaders; one without is treated as current.)
    const belongsToCurrentSession =
      (originalRequest._sessionEpoch ?? sessionEpoch) === sessionEpoch && !originalRequest._skipAuthRefresh;

    if (error.response?.status === 401 && !originalRequest._retried && !isAuthEndpoint && belongsToCurrentSession) {
      originalRequest._retried = true;

      // Reading the stored token, rotating it and persisting the result all happen inside
      // refreshAccessToken so they cannot be interleaved -- see its comment for the session-wide
      // sign-out that splitting them caused. A missing token throws there too, so "no session" and
      // "refresh rejected" land on the same branch, which is what they both mean here.
      const outcome = await renewSession();
      if (outcome.kind === 'renewed') {
        originalRequest.headers.Authorization = `Bearer ${outcome.pair.token}`;
        return api(originalRequest);
      }
      // The session this request belonged to ended while the refresh ran. Nothing to report and
      // nothing to clear: storage may already hold the next session.
      if (outcome.kind === 'ended') return Promise.reject(error);
      // The refresh never reached the server (no internet, or rate limited): the session is fine.
      // Only this request fails, the way any request fails offline; the next one refreshes again.
      if (outcome.kind === 'kept') return Promise.reject(error);
      const refreshErr = outcome.error;
      // Every request waiting on the same refused refresh lands here. The first one ends the
      // session (markSessionEnded runs synchronously inside clearSessionAndRedirect); the rest
      // belong to a session that has already ended, so they sign nothing out a second time.
      if ((originalRequest._sessionEpoch ?? sessionEpoch) !== sessionEpoch) return Promise.reject(error);
      // An info event, not an error: an idle or expired session ending is routine. It exists so the
      // reason is on record the next time someone is signed out unexpectedly.
      const now = Date.now();
      if (now - lastRefreshFailureReportedAt >= REFRESH_FAILURE_REPORT_INTERVAL_MS) {
        lastRefreshFailureReportedAt = now;
        reportHandledEvent('Session refresh failed; signing out', 'session-refresh-failed', describeRefreshFailure(refreshErr));
      }
      // A refresh refused because the account is now suspended carries that reason to the sign-in
      // screen (its access token had already expired, so no request got the 403 AUTH_018 below).
      await signOutAfterRefusedRefresh(refreshErr);
      return Promise.reject(error);
    }

    // Backend is the source of truth on phone verification (PhoneVerificationFilter) -- a valid
    // session that hasn't completed verification yet. Send the app to finish it via the
    // registered callback rather than leaving every subsequent call silently failing.
    if (error.response?.status === 403 && error.response?.data?.errorCode === 'PHONE_VERIFICATION_REQUIRED') {
      onPhoneVerificationRequired();
      return Promise.reject(error);
    }

    // Privacy workstream 10. The account was suspended while this phone was signed in: every
    // request the session makes is now refused with AUTH_018 until its access token expires (and
    // the refresh after that is refused too). End the session now, with the reason, so the user can
    // sign in again and reach the suspended screen. Mirrors the web app's client.ts.
    //
    // Only for a request sent under the app's own session. The suspended screen's own calls carry
    // their token per request and handle their own errors, and a sign-in refusal (also AUTH_018)
    // goes to an auth endpoint, which never carries the session. A request from a session that has
    // already ended does nothing: the first AUTH_018 ended it, and the rest of a burst must not
    // sign out whoever signs in next.
    if (
      error.response?.status === 403 &&
      error.response?.data?.errorCode === AUTH_ACCOUNT_SUSPENDED &&
      originalRequest?._sentWithSession &&
      belongsToCurrentSession
    ) {
      await clearSessionAndRedirect(false, SUSPENDED_SIGN_OUT_MESSAGE);
      return Promise.reject(error);
    }

    // Error responses use the same envelope ({success:false, message, errorCode, details}) --
    // surface the message where callers already expect err.response.data.message. `details` is
    // carried through too (not just message/errorCode): AUTH_ACCOUNT_DEACTIVATED's reactivation
    // token travels there (see ApiException/ApiResponse on the backend), same as the web app's
    // client.ts -- dropping it silently would make the reactivation flow unreachable here too.
    if (error.response?.data?.message) {
      error.response.data = {
        message: error.response.data.message,
        errorCode: error.response.data.errorCode,
        details: error.response.data.details,
        // Whether the user themselves can fix what caused this -- computed once, backend-side,
        // from ErrorCode.userActionRequired() (GlobalExceptionHandler), not re-derived here.
        // Absent (undefined) for a codeless ApiException, which has no classification to offer;
        // callers treat that the same as false, never guessing a failure into looking actionable.
        // Mirrors the same flattening the web app's client.ts does, so a future port of a
        // web screen that reads `err.response?.data?.userActionRequired` behaves the same here.
        userActionRequired: error.response.data.details?.userActionRequired,
      };
    }
    return Promise.reject(error);
  }
);
