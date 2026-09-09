import { useCallback, useEffect, useRef } from 'react';
import type { NavigationContainerRefWithCurrent } from '@react-navigation/native';
import { defaultMessaging, type PushMessaging, type RemoteMessage } from '../lib/pushRegistration';
import type { RootParamList } from './types';

/**
 * Phase 5 (Low-Priority Polish). A tapped push notification currently opens the app to wherever
 * it happens to land (whatever screen was last on screen, or the default Home tab on a cold
 * start) -- the notification's own content is never acted on. This routes the tap to the screen
 * it's actually about, by the same `type` FirebaseFcmMessageSender now attaches as FCM data (see
 * that class's own doc comment).
 *
 * <h2>Only routes by type, never to a specific entity</h2>
 *
 * The `Notification` domain entity carries no target/reference-entity id column at all (confirmed
 * by reading it directly, not assumed) -- there is nothing here to deep-link a
 * IMPORT_STATEMENT_READY tap to the one statement it's about, only to the screen that lists every
 * statement. That is the ceiling of what this can do without a backend schema change; routing by
 * type is still a real improvement over today's "wherever the app happens to be."
 *
 * <h2>Two distinct signals, not one</h2>
 *
 * `onNotificationOpenedApp` fires when a BACKGROUNDED (not killed) app is brought forward by a
 * notification tap -- the JS engine and this listener are already alive. `getInitialNotification`
 * answers a different question: did THIS cold launch happen because of a notification tap, for an
 * app that was fully killed? Both are needed; relying on only one silently drops the other launch
 * path -- FCM's own documented behavior, not a workaround.
 *
 * <h2>Gating borrows from both useEmailChangeDeepLink and useReferralDeepLink</h2>
 *
 * A tapped push should survive the same kind of transient `ready` dip useEmailChangeDeepLink
 * already accounts for (e.g. the tap lands mid a phone re-verification challenge) -- so `ready`
 * gates consumption (in tryConsume) the same way, and clearing on a real sign-out (`signedIn`
 * true -> false) is copied verbatim from that hook's own D6 fix.
 *
 * But UNLIKE useEmailChangeDeepLink, `handleMessage` also borrows useReferralDeepLink's "don't
 * stash while inactive" rule, gated on `signedIn` specifically (not `ready`, which a still-signed-
 * in user legitimately dips through): a system notification tapped while genuinely signed out --
 * most plausibly one that was delivered and sat in the tray before sign-out, since sign-out
 * revokes this device's token but cannot retract an already-shown notification -- is dropped
 * outright rather than stashed. Both of this hook's destinations (Settings, Statements) are
 * generic, backend-scoped-to-whoever's-signed-in screens with no equivalent of the email link's
 * own findByIdAndUserId rejection, so nothing else would stop a stale tap from opening the wrong
 * screen for whoever signs in next on the device -- unlike the confirmed-harmless replay
 * useEmailChangeDeepLink's own doc comment describes for its case.
 */
export interface PushNotificationNavigationDeps {
  messaging?: PushMessaging;
}

/** The only two destinations a notification `type` can resolve to -- kept as literals (not
 *  `keyof MoreStackParamList`) so `navigationRef.navigate('More', { screen: ... })` below stays
 *  inside NavigatorScreenParams' discriminated union instead of a widened, unnavigable one. */
type PushRoute = 'Settings' | 'Statements';

const TYPE_ROUTE: Record<string, PushRoute> = {
  PASSWORD_CHANGED: 'Settings',
  IMPORT_STATEMENT_READY: 'Statements',
  IMPORT_STATEMENT_HELD: 'Statements',
};

function routeFor(message: RemoteMessage): PushRoute | null {
  const type = message.data?.type;
  if (typeof type !== 'string') return null;
  return TYPE_ROUTE[type] ?? null;
}

export function usePushNotificationNavigation(
  navigationRef: NavigationContainerRefWithCurrent<RootParamList>,
  ready: boolean,
  signedIn: boolean,
  deps: PushNotificationNavigationDeps = {},
) {
  const pendingRef = useRef<PushRoute | null>(null);
  const readyRef = useRef(ready);
  const signedInRef = useRef(signedIn);
  const wasSignedInRef = useRef(signedIn);

  const tryConsume = useCallback(() => {
    if (!readyRef.current) return;
    if (!navigationRef.current || !navigationRef.isReady()) return;
    const route = pendingRef.current;
    if (!route) return;
    pendingRef.current = null;
    // A literal on each branch, not `{ screen: route }` -- see PushRoute's own doc comment.
    if (route === 'Settings') navigationRef.navigate('More', { screen: 'Settings' });
    else navigationRef.navigate('More', { screen: 'Statements' });
  }, [navigationRef]);

  useEffect(() => {
    function handleMessage(message: RemoteMessage) {
      const route = routeFor(message);
      if (!route) return;
      // Bug fix: this used to stash unconditionally, relying only on the sign-out effect below to
      // clear a stale route. That effect only fires on a TRUE -> false transition, so a tap on an
      // already-delivered system notification, opened while ALREADY fully signed out (the
      // notification sat in the tray since before sign-out; revoking this device's token on
      // sign-out doesn't retract it), would sit in pendingRef with no transition to clear it --
      // and replay for whoever signs in NEXT on this device, not the identity the push was for.
      // Gated on `signedIn`, not `readyRef` (checked at consume time in tryConsume): a transient
      // ready dip for a still-signed-in user must still stash and later replay, same as
      // useEmailChangeDeepLink's own ready/signedIn distinction this hook otherwise mirrors.
      if (!signedInRef.current) return;
      pendingRef.current = route;
      tryConsume();
    }

    let unsubscribeOpened = () => {};
    try {
      const messaging = deps.messaging ?? defaultMessaging();
      unsubscribeOpened = messaging.onNotificationOpenedApp(handleMessage);
      void messaging.getInitialNotification().then((message) => { if (message) handleMessage(message); });
    } catch {
      // Same "push is an enhancement, not a requirement" posture as pushRegistration.ts's own
      // functions -- a broken/absent native messaging module must never break navigation.
    }
    return () => unsubscribeOpened();
    // deps.messaging is intentionally not a dependency: production callers pass no override at
    // all (a fresh {} every render would otherwise resubscribe the native listener every render).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tryConsume]);

  useEffect(() => {
    readyRef.current = ready;
    signedInRef.current = signedIn;
    // Mirrors useEmailChangeDeepLink's own D6 fix verbatim -- see that hook's doc comment for the
    // failure this guards: only a genuine sign-out (signedIn true -> false) should drop a still-
    // pending route, never a transient ready dip for a user who never signed out.
    if (wasSignedInRef.current && !signedIn) {
      pendingRef.current = null;
    }
    wasSignedInRef.current = signedIn;
    tryConsume();
  }, [ready, signedIn, tryConsume]);

  return { onNavigationReady: tryConsume };
}
