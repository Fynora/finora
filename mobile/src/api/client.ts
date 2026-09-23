import axios from 'axios';
import * as SecureStore from 'expo-secure-store';
import { safeStorage } from '../lib/safeStorage';
import { reportHandledEvent } from '../lib/monitoring';
import { APP_VERSION, PLATFORM_HEADER, VERSION_HEADER, clientPlatform } from '../lib/clientIdentity';

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
const AUTH_ENDPOINTS_NO_TOKEN = ['/auth/login', '/auth/register', '/auth/refresh', '/auth/forgot-password', '/auth/reset-password', '/auth/reactivate', '/auth/google', '/auth/apple', '/auth/identify', '/auth/otp/email/request', '/auth/otp/email/login', '/auth/otp/phone/login'];

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
  const isAuthEndpoint = AUTH_ENDPOINTS_NO_TOKEN.some((path) => pathMatchesAuthEndpoint(config.url, path));
  if (!isAuthEndpoint) {
    const token = await safeStorage.getItem(TOKEN_KEY);
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
  }
  // Which app and which build, for support tickets and feedback to record. Sent on every request
  // rather than only the endpoints that store it, so the contract is one rule instead of a list
  // that drifts -- the same shape the web client uses. See lib/clientIdentity.ts.
  config.headers[PLATFORM_HEADER] = clientPlatform();
  config.headers[VERSION_HEADER] = APP_VERSION;
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
let onSessionExpired: SessionCallback = () => {};
let onPhoneVerificationRequired: SessionCallback = () => {};

export function setSessionCallbacks(handlers: {
  onSessionExpired?: SessionCallback;
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

// Mirrors every key AuthContext.logout() clears on the web app.
async function clearSessionAndRedirect() {
  await Promise.all([
    safeStorage.removeItem(TOKEN_KEY),
    safeStorage.removeItem(REFRESH_TOKEN_KEY),
    safeStorage.removeItem('finora_email'),
    safeStorage.removeItem('finora_name'),
    safeStorage.removeItem('finora_phone_verified'),
  ]);
  onSessionExpired();
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
let refreshInFlight: Promise<{ token: string; refreshToken: string }> | null = null;

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
function refreshAccessToken(): Promise<{ token: string; refreshToken: string }> {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      const stored = await safeStorage.getItem(REFRESH_TOKEN_KEY);
      if (!stored) throw new Error(NO_REFRESH_TOKEN_MESSAGE);
      const { authApi } = await import('./endpoints');
      const refreshed = await authApi.refresh(stored);
      await SecureStore.setItemAsync(TOKEN_KEY, refreshed.token);
      await SecureStore.setItemAsync(REFRESH_TOKEN_KEY, refreshed.refreshToken);
      return refreshed;
    })().finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
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

    if (error.response?.status === 401 && !originalRequest._retried && !isAuthEndpoint) {
      originalRequest._retried = true;

      // Reading the stored token, rotating it and persisting the result all happen inside
      // refreshAccessToken so they cannot be interleaved -- see its comment for the session-wide
      // sign-out that splitting them caused. A missing token throws there too, so "no session" and
      // "refresh rejected" land on the same branch, which is what they both mean here.
      try {
        const refreshed = await refreshAccessToken();
        originalRequest.headers.Authorization = `Bearer ${refreshed.token}`;
        return api(originalRequest);
      } catch (refreshErr) {
        // An info event, not an error: an idle or expired session ending is routine. It exists so the
        // reason is on record the next time someone is signed out unexpectedly.
        const now = Date.now();
        if (now - lastRefreshFailureReportedAt >= REFRESH_FAILURE_REPORT_INTERVAL_MS) {
          lastRefreshFailureReportedAt = now;
          reportHandledEvent('Session refresh failed; signing out', 'session-refresh-failed', describeRefreshFailure(refreshErr));
        }
        await clearSessionAndRedirect();
        return Promise.reject(error);
      }
    }

    // Backend is the source of truth on phone verification (PhoneVerificationFilter) -- a valid
    // session that hasn't completed verification yet. Send the app to finish it via the
    // registered callback rather than leaving every subsequent call silently failing.
    if (error.response?.status === 403 && error.response?.data?.errorCode === 'PHONE_VERIFICATION_REQUIRED') {
      onPhoneVerificationRequired();
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
