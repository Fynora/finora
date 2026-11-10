import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { AppState, type AppStateStatus, BackHandler, Keyboard, StyleSheet, Text, View } from 'react-native';
import { AppAlertOverlay, useAlertShowing } from './AppAlertOverlay';
import { AppBannerOverlay } from './AppBannerOverlay';
import { AppCoveredProvider, useLaunchCovering } from './AppModal';
import { Button } from './Button';
import { PinPad } from './PinPad';
import { PinSetup } from './PinSetup';
import { verifySession } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { ROOT_ALERT_CONTAINER } from '../lib/appAlert';
import * as appLock from '../lib/appLock';
import * as pinLock from '../lib/pinLock';
import { spacing, useTheme } from '../theme';

/**
 * The app lock (SEC-09; PIN and biometrics since the 2026-11-10 design). A signed-in phone keeps
 * its session for up to 90 days unused, so opening the app always needs the Fynora PIN, or Face ID
 * / fingerprint if the user turned that on.
 *
 * - Signed in, no PIN yet (just signed in, or first open after updating): PinSetup, before anything
 *   else is mounted. No skip.
 * - Cold start with a saved session: locked. One refresh (verifySession) asks the server whether the
 *   session is still alive. Ended: straight to the sign-in screen, no prompt -- the bug this
 *   replaced asked for Face ID on a session that was already over, then showed sign-in. Offline:
 *   still locked and still unlockable, because the app works on cached data.
 * - Back from the background after more than RELOCK_AFTER_MS: locked again, over the still-mounted
 *   app, so an unlock returns to the same screen with its state. Shorter trips do not lock.
 * - 5 wrong PINs, or "Forgot PIN?": signed out on this phone.
 *
 * The biometric prompt only ever fires while a session is present, and is held while the launch
 * animation plays (the system sheet would draw over it).
 */

// Sentinel for `gate.token` -- distinct from any real token and from `null` (signed out), so the
// very first render never matches `token`.
const NEVER_CHECKED = Symbol('app-lock-never-checked');

/** How long the app may sit in the background before it locks again (design 2026-11-10). */
export const RELOCK_AFTER_MS = 60_000;

// How long after ANY authenticate() call, PIN check or share resolves to keep treating a
// foreground transition as caused by it rather than by the user leaving. On-device testing showed
// the native "app became active" notification for a dismissed Face ID sheet can arrive AFTER
// authenticateAsync()'s own promise resolved, so guarding only while a call is in flight is not
// enough. Generous on purpose: too short re-prompts in a loop; too long skips one relock check.
const REGROUND_GRACE_MS = 1500;

type Phase = 'setup' | 'locked' | 'open';
type Verify = 'pending' | 'alive' | 'offline';

function wrongPinMessage(triesLeft: number): string {
  if (triesLeft > 2) return 'Wrong PIN. Try again.';
  return `Wrong PIN. ${triesLeft} ${triesLeft === 1 ? 'try' : 'tries'} left.`;
}

export function AppLockGate({ children }: { children: ReactNode }) {
  const { bootstrapping, token, logout } = useAuth();
  const c = useTheme();

  // Which token the lock decision applies to, and what it is. A stale value simply stops matching
  // `token` the moment a session ends or a new one starts, so nothing has to be reset by hand.
  const [gate, setGate] = useState<{ token: string | null | typeof NEVER_CHECKED; phase: Phase }>({
    token: NEVER_CHECKED,
    phase: 'open',
  });
  const checked = token !== null && gate.token === token;
  const phase: Phase = checked ? gate.phase : 'open';
  const [verify, setVerify] = useState<{ token: string | null; result: Verify }>({ token: null, result: 'pending' });
  const verifyResult: Verify = verify.token === token ? verify.result : 'pending';
  const [biometricOn, setBiometricOn] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirmForgot, setConfirmForgot] = useState(false);
  // Bumped each time a lock wants its biometric prompt; the prompt effect consumes it.
  const [promptRequest, setPromptRequest] = useState(0);

  // Which token's session has already had its app shown at least once. Until then a locked session
  // gets ONLY the lock screen -- nothing protected is mounted before the first unlock. After that
  // the lock screen goes ON TOP of the still-mounted app. Adjusted during render, keyed by token.
  const [openedToken, setOpenedToken] = useState<string | null | typeof NEVER_CHECKED>(NEVER_CHECKED);
  const opened = token !== null && openedToken === token;
  if (token !== null && checked && phase === 'open' && !opened) setOpenedToken(token);

  // The token bootstrap restored from storage, as opposed to one from a sign-in in this run of the
  // app. Only a restored session needs asking the server about: a fresh sign-in just proved it.
  const restoredTokenRef = useRef<string | null | undefined>(undefined);
  const tokenRef = useRef(token);
  const handledPromptRef = useRef(0);
  const appState = useRef(AppState.currentState);
  const backgroundAtRef = useRef<number | null>(null);
  // Set when the app leaves the foreground while a prompt, PIN check or share is already in flight:
  // that departure is what the sheet itself caused, so the matching return must not lock, however
  // long the sheet takes to dismiss.
  const selfInducedBackgroundRef = useRef(false);

  const sessionActive = !bootstrapping && token !== null;
  const locked = sessionActive && (!checked || phase !== 'open');

  useEffect(() => {
    tokenRef.current = token;
  }, [token]);

  // Mirrors the lock into appLock's shared flag -- storeReview and AuthContext's push handling,
  // outside this subtree, read it to stay out of the way while the lock or PIN setup is up.
  useEffect(() => {
    appLock.setLockedFlag(locked);
  }, [locked]);

  // The app stays mounted under the lock screen, so its navigator is still listening for Android's
  // back button: without this, back on the lock screen would pop a screen the user cannot see.
  const lockedOver = sessionActive && checked && phase === 'locked' && opened;
  useEffect(() => {
    if (!lockedOver) return;
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      BackHandler.exitApp();
      return true;
    });
    return () => subscription.remove();
  }, [lockedOver]);

  const alertUp = useAlertShowing(ROOT_ALERT_CONTAINER);
  const launching = useLaunchCovering();

  /** Locks `forToken`'s session and asks for the biometric prompt that goes with it. */
  const lock = useCallback((forToken: string) => {
    // The app underneath may stay mounted, so a focused input would otherwise keep the keyboard up
    // and swallow typing into a screen no one can see.
    Keyboard.dismiss();
    setError(null);
    setConfirmForgot(false);
    setGate({ token: forToken, phase: 'locked' });
    setPromptRequest((n) => n + 1);
    // Read fresh each time: Settings can turn biometrics on or off mid-session.
    void appLock.isEnabled().then((on) => {
      if (tokenRef.current === forToken) setBiometricOn(on);
    });
  }, []);

  const tryBiometric = useCallback(async () => {
    const forToken = tokenRef.current;
    // Never for a session that is not there: the bug this replaced asked for Face ID over the
    // sign-in screen after the session had ended under the launch animation.
    if (forToken === null) return;
    setBusy(true);
    try {
      const ok = await appLock.authenticate('Unlock Fynora');
      if (ok && tokenRef.current === forToken) {
        await pinLock.resetFailures();
        setError(null);
        setGate({ token: forToken, phase: 'open' });
      }
    } finally {
      setBusy(false);
    }
  }, []);

  // Records which token bootstrap restored. Declared before the check below so it runs first in
  // the same commit.
  useEffect(() => {
    if (!bootstrapping && restoredTokenRef.current === undefined) restoredTokenRef.current = token;
  }, [bootstrapping, token]);

  // The lock decision for each new session.
  useEffect(() => {
    if (bootstrapping || token === null) return;
    let cancelled = false;
    const restored = restoredTokenRef.current === token;
    if (restored) {
      void verifySession().then((result) => {
        // 'ended' needs nothing here: the session is cleared and `token` goes to null.
        if (!cancelled && result !== 'ended') setVerify({ token, result });
      });
    }
    void pinLock.hasPin().then((hasPin) => {
      if (cancelled) return;
      if (!restored) setVerify({ token, result: 'alive' });
      if (!hasPin) {
        setGate({ token, phase: 'setup' });
        return;
      }
      lock(token);
    });
    return () => {
      cancelled = true;
    };
  }, [bootstrapping, token, lock]);

  // The biometric prompt that goes with a lock: only once the server has not said the session is
  // over, never under the launch animation, and only while the session is still here. From a timer
  // rather than the effect body, which must not set state synchronously (tryBiometric does).
  const promptReady = sessionActive && checked && phase === 'locked' && biometricOn && !launching
    && verifyResult !== 'pending';
  useEffect(() => {
    if (!promptReady || promptRequest === handledPromptRef.current) return;
    const timer = setTimeout(() => {
      handledPromptRef.current = promptRequest;
      void tryBiometric();
    }, 0);
    return () => clearTimeout(timer);
  }, [promptReady, promptRequest, tryBiometric]);

  // Locks again after more than RELOCK_AFTER_MS in the background. A trip caused by the app's own
  // Face ID sheet, PIN check or share sheet is not the user leaving and never locks.
  const gateRef = useRef(gate);
  useEffect(() => {
    gateRef.current = gate;
  }, [gate]);
  useEffect(() => {
    const subscription = AppState.addEventListener('change', (next: AppStateStatus) => {
      // AppState.currentState is nullable until the native module answers.
      const wasActive = appState.current === 'active';
      const cameToForeground = !!appState.current?.match(/inactive|background/) && next === 'active';
      if (next.match(/inactive|background/)) {
        if (appLock.isAuthenticating() || appLock.isSharing()) selfInducedBackgroundRef.current = true;
        // The first step out of the foreground starts the clock; iOS often fires 'inactive' then
        // 'background', and the second must not restart it.
        if (wasActive) backgroundAtRef.current = Date.now();
      }
      appState.current = next;
      if (!cameToForeground) return;
      const selfInduced = selfInducedBackgroundRef.current;
      selfInducedBackgroundRef.current = false;
      const skip = selfInduced
        || appLock.isAuthenticating() || appLock.justFinishedAuthenticating(REGROUND_GRACE_MS)
        || appLock.isSharing() || appLock.justFinishedSharing(REGROUND_GRACE_MS);
      const leftAt = backgroundAtRef.current;
      backgroundAtRef.current = null;
      const current = tokenRef.current;
      const g = gateRef.current;
      // Only an open session locks again; a lock or PIN setup already up stays as it is.
      if (skip || current === null || leftAt === null || g.token !== current || g.phase !== 'open') return;
      if (Date.now() - leftAt > RELOCK_AFTER_MS) lock(current);
    });
    return () => subscription.remove();
  }, [lock]);

  async function submitPin(pin: string) {
    const forToken = tokenRef.current;
    if (forToken === null) return;
    setBusy(true);
    try {
      const result = await pinLock.checkPin(pin);
      if (tokenRef.current !== forToken) return;
      if (result.ok) {
        setError(null);
        setGate({ token: forToken, phase: 'open' });
        return;
      }
      if (result.triesLeft === 0) {
        logout();
        return;
      }
      setError(wrongPinMessage(result.triesLeft));
    } finally {
      setBusy(false);
    }
  }

  async function finishSetup() {
    const forToken = tokenRef.current;
    const on = await appLock.isEnabled();
    if (forToken === null || tokenRef.current !== forToken) return;
    setBiometricOn(on);
    setGate({ token: forToken, phase: 'open' });
  }

  const forgotFooter = confirmForgot ? (
    <View style={styles.forgot}>
      <Text style={[styles.forgotText, { color: c.muted }]}>
        You'll be signed out on this phone. Sign in again, then set a new PIN.
      </Text>
      <Button label="Sign out" onPress={logout} variant="secondary" />
      <Button label="Cancel" onPress={() => setConfirmForgot(false)} variant="link" />
    </View>
  ) : (
    <Button label="Forgot PIN?" onPress={() => setConfirmForgot(true)} variant="link" />
  );

  // The lock UI appears in two shapes: alone (a cold-start lock: nothing protected is mounted yet)
  // or as an overlay over the already-mounted app (every later lock).
  const lockScreen = (overlay: boolean) => (
    <View style={[styles.fill, overlay ? styles.cover : null, { backgroundColor: c.bg } /* glass-exempt: lock screen must hide the app; never translucent */]}>
      <PinPad
        title="Enter your Fynora PIN"
        error={error}
        busy={busy}
        onComplete={(pin) => void submitPin(pin)}
        onBiometric={biometricOn ? () => void tryBiometric() : undefined}
        footer={forgotFooter}
      />
    </View>
  );

  if (sessionActive) {
    // The decision for THIS session is not in yet: render nothing rather than `children`, so the
    // app cannot paint for one commit before a lock or PIN setup replaces it.
    if (!checked) return null;
    if (phase === 'setup') {
      return (
        <View style={[styles.fill, { backgroundColor: c.bg } /* glass-exempt: PIN setup must hide the app; never translucent */]}>
          <PinSetup onDone={() => void finishSetup()} />
        </View>
      );
    }
    // Locked before the app was ever shown this session: the lock screen alone, nothing mounted.
    if (phase === 'locked' && !opened) return lockScreen(false);
  }

  const showLock = sessionActive && checked && phase === 'locked';
  // Hidden from touch and screen readers while an alert is up too: the alert is drawn in this tree,
  // so without this a screen reader could wander onto the screen behind it.
  const appHidden = showLock || alertUp;
  // ONE stable shape for every state where children are shown (signed out, bootstrapping,
  // unlocked, locked over an opened app) -- a different wrapper per state would make React remount
  // the whole app tree every time the state flips, discarding the navigator's position.
  return (
    <View style={styles.fill}>
      <View
        style={styles.fill}
        pointerEvents={appHidden ? 'none' : 'auto'}
        importantForAccessibility={appHidden ? 'no-hide-descendants' : 'auto'}
        accessibilityElementsHidden={appHidden}
      >
        {/* Native Modals sit above this whole overlay, so they have to hide themselves -- see
            AppModal. */}
        <AppCoveredProvider value={showLock}>{children}</AppCoveredProvider>
      </View>
      {/* Alerts are drawn by the app (a native Alert.alert could not be hidden once open): while
          locked one keeps waiting underneath the lock screen, unreachable -- see AppAlertOverlay. */}
      <AppAlertOverlay containerId={ROOT_ALERT_CONTAINER} hidden={showLock} />
      {/* A passing banner (an admin campaign push while the app is open): never over the lock. */}
      <AppBannerOverlay containerId={ROOT_ALERT_CONTAINER} hidden={showLock} />
      {showLock ? lockScreen(true) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  cover: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 },
  forgot: { width: '100%', alignItems: 'center', gap: spacing.sm, marginTop: spacing.md },
  forgotText: { fontSize: 14, textAlign: 'center' },
});
