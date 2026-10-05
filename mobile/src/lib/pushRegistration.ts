import { PermissionsAndroid, Platform } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import {
  deleteToken as fbDeleteToken, getInitialNotification as fbGetInitialNotification, getMessaging,
  getToken as fbGetToken, onMessage as fbOnMessage, onNotificationOpenedApp as fbOnNotificationOpenedApp,
  onTokenRefresh as fbOnTokenRefresh, requestPermission as fbRequestPermission,
  type RemoteMessage,
} from '@react-native-firebase/messaging';
import { deviceTokensApi, type DevicePlatform } from '../api/endpoints';

export type { RemoteMessage };

/**
 * Task 14 -- the mobile half of push. Without this module, Task 9's POST /device-tokens endpoint
 * is never called: no `device_tokens` row is ever written, and every push silently no-ops while
 * the backend test suite (and every other layer) stays green. See AuthContext.tsx for where these
 * two functions are actually wired into the session lifecycle.
 *
 * Both exported functions take their collaborators (`messaging`, `postDeviceToken`/
 * `deleteDeviceToken`) as optional dependency-injected arguments, defaulting to the real
 * @react-native-firebase/messaging module and the real backend endpoints. Tests supply fakes;
 * production callers call these with no arguments at all.
 */

/** A minimal, method-shaped view over the modular @react-native-firebase/messaging API -- built
 *  once by defaultMessaging() below, or substituted wholesale by a test. */
export interface PushMessaging {
  requestPermission(): Promise<number>;
  getToken(): Promise<string>;
  /** Invalidates this install's push token with Firebase; the next getToken() mints a new one. */
  deleteToken(): Promise<void>;
  onTokenRefresh(listener: (token: string) => void): () => void;
  onMessage(listener: (message: RemoteMessage) => void): () => void;
  // Phase 5 (Low-Priority Polish). Fires when a background (not killed) app is opened by tapping
  // a system notification -- usePushNotificationNavigation.ts's own doc comment covers why this
  // and getInitialNotification below are two separate signals, not one.
  onNotificationOpenedApp(listener: (message: RemoteMessage) => void): () => void;
  getInitialNotification(): Promise<RemoteMessage | null>;
}

export type PostDeviceTokenFn = (body: { token: string; platform: DevicePlatform }) => Promise<unknown>;

let cachedMessaging: PushMessaging | null = null;

/** Lazily wraps the real modular API into the method-shaped PushMessaging interface above.
 *  Lazy (not built at module scope) so importing this file never touches the native module --
 *  only calling registerDeviceToken()/detachDevice()/usePushNotificationNavigation with no
 *  override does. Exported so usePushNotificationNavigation.ts shares this exact instance/cache
 *  rather than building a second wrapper around the same native module. */
export function defaultMessaging(): PushMessaging {
  if (!cachedMessaging) {
    const instance = getMessaging();
    cachedMessaging = {
      requestPermission: () => fbRequestPermission(instance),
      getToken: () => fbGetToken(instance),
      deleteToken: () => fbDeleteToken(instance),
      onTokenRefresh: (listener) => fbOnTokenRefresh(instance, listener),
      onMessage: (listener) => fbOnMessage(instance, listener),
      onNotificationOpenedApp: (listener) => fbOnNotificationOpenedApp(instance, listener),
      getInitialNotification: () => fbGetInitialNotification(instance),
    };
  }
  return cachedMessaging;
}

function defaultPostDeviceToken(body: { token: string; platform: DevicePlatform }) {
  return deviceTokensApi.register(body);
}

function currentPlatform(): DevicePlatform {
  return Platform.OS === 'android' ? 'ANDROID' : 'IOS';
}

/**
 * Mirrors @react-native-firebase/messaging's own AuthorizationStatus enum (NOT_DETERMINED: -1,
 * DENIED: 0, AUTHORIZED: 1, PROVISIONAL: 2, EPHEMERAL: 3) as plain numbers rather than importing
 * it -- this file's only import from the package is the handful of functions defaultMessaging()
 * wraps, so a test's mock of that module never has to reproduce this enum too.
 */
const GRANTED_AUTHORIZATION_STATUSES = new Set([1, 2]);

/**
 * iOS always needs an explicit prompt (messaging.requestPermission()). Android needs the
 * POST_NOTIFICATIONS *runtime* permission only on API 33+ -- below that it's implicit -- and
 * critically, RNFB's own requestPermission() does NOT surface that grant on Android: the native
 * module's Android implementation (NativeRNFBTurboMessaging#requestPermission) unconditionally
 * resolves AUTHORIZED regardless of the real OS permission state. So Android's real signal has to
 * come from PermissionsAndroid directly, checked first and short-circuiting on denial.
 */
async function ensureNotificationPermission(messaging: PushMessaging): Promise<boolean> {
  if (Platform.OS === 'android' && typeof Platform.Version === 'number' && Platform.Version >= 33) {
    const result = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
    if (result !== PermissionsAndroid.RESULTS.GRANTED) return false;
  }
  const status = await messaging.requestPermission();
  return GRANTED_AUTHORIZATION_STATUSES.has(status);
}

/**
 * Logs enough to debug a registration failure without ever writing a raw device token to the
 * device's logs. Deliberately narrow: an Axios error's `.config.data` is the exact JSON body just
 * sent, which for this call IS the token -- logging the error object whole (or anything under
 * `.config`) would leak it. Only a message and an HTTP status code (when present) are safe.
 */
function logPushFailure(context: string, error: unknown): void {
  const status = (error as { response?: { status?: number } } | null)?.response?.status;
  const message = error instanceof Error ? error.message : 'unknown error';
  console.warn(`[pushRegistration] ${context}`, { message, status });
}

async function postToken(postDeviceToken: PostDeviceTokenFn, token: string): Promise<void> {
  try {
    await postDeviceToken({ token, platform: currentPlatform() });
  } catch (error) {
    // A failed registration must never block the user or surface an error -- push is an
    // enhancement, not a requirement of using the app. Log and swallow.
    logPushFailure('failed to register device token', error);
  }
}

/** Unsubscribes whatever onTokenRefresh listener a previous registerDeviceToken() call attached,
 *  so repeated calls (login, then a later foreground) don't stack up duplicate listeners each
 *  re-posting the same rotated token. */
let unsubscribeTokenRefresh: (() => void) | null = null;

export interface RegisterDeviceTokenDeps {
  postDeviceToken?: PostDeviceTokenFn;
  messaging?: PushMessaging;
}

/**
 * Requests notification permission, and on grant, registers this device's current FCM token with
 * the backend and stays subscribed to future rotations (Firebase rotates the token on reinstall,
 * restore, and app-data clear -- without re-posting then, the user silently stops receiving push).
 *
 * Never throws. A denied prompt is a normal outcome, not an error, and is handled identically to
 * any other reason no token gets registered: the function simply returns.
 *
 * Callers (AuthContext) MUST NOT call this before phone verification has completed --
 * PhoneVerificationFilter on the backend 403s POST /device-tokens for a verified-pending session
 * the same as it would any other unexempted endpoint, and a failed registration here would just be
 * silently swallowed, leaving no token ever stored for that user.
 */
export async function registerDeviceToken(deps: RegisterDeviceTokenDeps = {}): Promise<void> {
  // Resolved INSIDE the try block, deliberately: defaultMessaging() calls the real, synchronous
  // getMessaging(), which can throw (e.g. no native Firebase app registered yet). An async
  // function auto-wraps a synchronous throw into a rejected promise rather than throwing back to
  // the caller -- but a caller that fires this with `void registerDeviceToken()` (every caller in
  // this app does) never attaches a .catch(), so an unresolved dependency built outside this try
  // block would surface as an unhandled promise rejection instead of the quiet log-and-swallow
  // this function promises everywhere else.
  try {
    const messaging = deps.messaging ?? defaultMessaging();
    const postDeviceToken = deps.postDeviceToken ?? defaultPostDeviceToken;

    const granted = await ensureNotificationPermission(messaging);
    if (!granted) return;

    const token = await messaging.getToken();
    if (!token) return;

    await postToken(postDeviceToken, token);

    unsubscribeTokenRefresh?.();
    unsubscribeTokenRefresh = messaging.onTokenRefresh((nextToken) => {
      if (!nextToken) return;
      void postToken(postDeviceToken, nextToken);
    });
  } catch (error) {
    logPushFailure('registerDeviceToken failed', error);
  }
}

/**
 * Set while a sign-out still owes Firebase a deleteToken(): written before the attempt, cleared once
 * it succeeds. A plain flag, not a credential, so AsyncStorage (fast) rather than SecureStore.
 */
export const PUSH_DETACH_PENDING_KEY = 'finora_push_detach_pending';

export interface DetachDeviceDeps {
  messaging?: PushMessaging;
}

/**
 * Sign-out's half of push: stops this phone receiving the account's notifications, without needing
 * the session's credentials. Returns the token it detached (or null), for the server-side revoke.
 *
 * Why the phone deletes its own token rather than relying on the server being told: the pushes
 * carry a visible notification (title + body), which the OS shows itself whether or not anyone is
 * signed in, and the server keeps sending to a registered token until another account registers
 * it. On 2026-10-05 a sign-out followed by an immediate swipe never reached the server, so that
 * phone stayed registered. Once Firebase deletes the token, every later send to it is rejected
 * UNREGISTERED and the backend revokes the row itself (FcmPushProvider) -- the server never has to
 * hear from this phone. If this attempt fails (offline, app closed mid-way), the pending flag makes
 * the next signed-out launch finish it (resumePendingDetach).
 *
 * Only an explicit sign-out calls this. A session that merely expires keeps its notifications --
 * still its owner's phone, and "your statement is ready" is what brings them back.
 *
 * Never throws.
 */
export async function detachDevice(deps: DetachDeviceDeps = {}): Promise<string | null> {
  // The listener first, in its own try: a broken native removal must not stop the rest, and it has
  // to be gone before deleteToken() so the token Firebase mints next is never posted for anyone.
  const unsubscribe = unsubscribeTokenRefresh;
  unsubscribeTokenRefresh = null;
  try {
    unsubscribe?.();
  } catch (error) {
    logPushFailure('failed to unsubscribe from token refresh', error);
  }

  let detached: string | null = null;
  try {
    await AsyncStorage.setItem(PUSH_DETACH_PENDING_KEY, '1');
    const messaging = deps.messaging ?? defaultMessaging();
    detached = (await messaging.getToken()) || null;
    await messaging.deleteToken();
    await AsyncStorage.removeItem(PUSH_DETACH_PENDING_KEY);
  } catch (error) {
    logPushFailure('detachDevice failed', error);
  }
  return detached;
}

/**
 * Finishes a sign-out's detachDevice() that did not complete. Call on a launch that finds no session;
 * a no-op unless the flag is set. `stillSignedOut` is checked again right before deleting, so a
 * sign-in that happened meanwhile keeps the token it just registered.
 *
 * Never throws.
 */
export async function resumePendingDetach(
  stillSignedOut: () => Promise<boolean>,
  deps: DetachDeviceDeps = {}
): Promise<void> {
  try {
    if ((await AsyncStorage.getItem(PUSH_DETACH_PENDING_KEY)) !== '1') return;
    if (!(await stillSignedOut())) return;
    const messaging = deps.messaging ?? defaultMessaging();
    await messaging.deleteToken();
    await AsyncStorage.removeItem(PUSH_DETACH_PENDING_KEY);
  } catch (error) {
    logPushFailure('resumePendingDetach failed', error);
  }
}

/** A sign-in owns this install's token from now on; an older sign-out's pending detach is void. */
export async function clearPendingDetach(): Promise<void> {
  try {
    await AsyncStorage.removeItem(PUSH_DETACH_PENDING_KEY);
  } catch (error) {
    logPushFailure('clearPendingDetach failed', error);
  }
}

export interface SubscribeToForegroundMessagesDeps {
  messaging?: PushMessaging;
}

/**
 * Subscribes to FCM messages that arrive while the app is in the foreground. Without this, a push
 * is unconditionally silent while the app is open: neither Android nor iOS shows a system
 * notification for a message received in the foreground on its own -- that's documented FCM
 * behavior on every platform, not a bug -- so the payload only ever reaches the app if something
 * here is listening for it.
 *
 * Every push this backend sends always carries a `notification` block (see
 * FirebaseFcmMessageSender#send -- title/body are fixed, plain strings on every call, never
 * data-only), so callers can treat `message.notification` as present; still typed optional here
 * because that's RemoteMessage's own real shape and a future data-only message must not throw.
 *
 * Never throws -- returns a no-op unsubscribe if messaging couldn't be resolved (e.g. no native
 * Firebase app registered), the same "push is an enhancement, not a requirement" posture as
 * registerDeviceToken/revokeDeviceToken above.
 */
export function subscribeToForegroundMessages(
  onMessage: (message: RemoteMessage) => void,
  deps: SubscribeToForegroundMessagesDeps = {}
): () => void {
  try {
    const messaging = deps.messaging ?? defaultMessaging();
    return messaging.onMessage(onMessage);
  } catch (error) {
    logPushFailure('subscribeToForegroundMessages failed', error);
    return () => {};
  }
}
