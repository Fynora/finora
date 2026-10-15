import * as Haptics from 'expo-haptics';
import { useSyncExternalStore } from 'react';
import { safeStorage } from './safeStorage';

/**
 * Every haptic touchpoint in the app funnels through here rather than calling `expo-haptics`
 * directly: one place to change the mapping from "kind of feedback" to Expo's three underlying
 * APIs (impact/notification/selection), and one place a test can assert against instead of every
 * screen that fires one. See src/test/setup.ts for the module-level jest.mock.
 *
 * expo-haptics' promises resolve once the native call is dispatched, not once it's felt, and
 * nothing here awaits them -- a haptic is a fire-and-forget side effect of a UI event, not
 * something a caller should block on or fail over if it rejects. `fire` swallows the rejection
 * itself (a device/OS where the native call actually fails -- vibration hardware unavailable, a
 * permission quirk on some Android OEM build) rather than leaving it unhandled: an ignored
 * rejection would otherwise surface as an unhandled-promise-rejection warning (and, once Sentry is
 * wired up in production, get reported as crash-adjacent noise) for a failure no caller here was
 * ever going to react to regardless.
 *
 * `fire` is also the one place the Haptics switch below is checked: the native call is passed as a
 * thunk so it does not even start unless the switch is on.
 */

/**
 * The in-app Haptics switch (Settings › General). Three states: 'unread' until the stored value has
 * been read at start-up, then 'on' or 'off'. Nothing fires while 'unread', so a person who turned
 * haptics off never feels one during start-up. A missing value, or a failed read (safeStorage
 * returns null on failure), means on. The phone's own settings still apply on top of this; the
 * switch is only ever an extra way to turn them off.
 */
type HapticsState = 'unread' | 'on' | 'off';
const STORAGE_KEY = 'finora_haptics';
let state: HapticsState = 'unread';
let primed = false;
/** A choice made before the start-up read lands wins over it. */
let chosen = false;
/** Saves run one after another, so fast toggling always leaves the last value in storage. */
let writes: Promise<void> = Promise.resolve();
const listeners = new Set<() => void>();
const setState = (next: HapticsState) => { state = next; listeners.forEach((l) => l()); };

export function primeHapticsSetting(): void {
  if (primed) return;
  primed = true;
  void safeStorage.getItem(STORAGE_KEY).then((stored) => {
    if (!chosen) setState(stored === 'off' ? 'off' : 'on');
  });
}

/** The switch was flipped: the state changes at once, turning it on fires one selection, then the
 *  value is saved. A failed haptic or a failed save does not move the switch back. */
export function setHapticsEnabled(enabled: boolean): void {
  chosen = true;
  setState(enabled ? 'on' : 'off');
  if (enabled) hapticSelection();
  const value = enabled ? 'on' : 'off';
  writes = writes.then(() => safeStorage.setItem(STORAGE_KEY, value));
}

export function useHapticsEnabled(): boolean {
  primeHapticsSetting();
  return useSyncExternalStore(
    (l) => { listeners.add(l); return () => listeners.delete(l); },
    () => state !== 'off',
  );
}

export function __resetHapticsForTests(): void {
  state = 'unread'; primed = false; chosen = false; writes = Promise.resolve(); listeners.clear();
}

export function __setHapticsStateForTests(next: HapticsState): void {
  state = next; primed = true; chosen = false;
}

function fire(kind: string, call: () => Promise<unknown>): void {
  if (state !== 'on') return;
  if (__DEV__) console.info(`[haptics] ${kind}`);
  call().catch(() => {});
}

/** A flow completed: an import finished, a budget saved. */
export function hapticSuccess(): void {
  fire('success', () => Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success));
}

/**
 * A validation error stopped a submit. Deliberately `Warning`, not `Error` -- nothing failed on
 * the server, the form just isn't complete yet, and iOS's own semantics keep those separate.
 */
export function hapticWarning(): void {
  fire('warning', () => Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning));
}

/**
 * A submit reached the server and failed there -- a network error, a rejected mutation. The
 * counterpart hapticWarning() is deliberately reserved for "the form isn't complete yet"; this is
 * for "the form was fine and it still didn't work," which is a different outcome the user should
 * be able to tell apart by feel, the same way iOS's own three-way Success/Warning/Error semantics
 * keep them apart.
 */
export function hapticError(): void {
  fire('error', () => Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error));
}

/** Picking one option among many -- a category, a filter -- where the feedback confirms the tap
 * registered rather than announcing an outcome. */
export function hapticSelection(): void {
  fire('selection', () => Haptics.selectionAsync());
}

/** A segment, chip or picker option was chosen: one tick, only when the value actually changes.
 *  Tapping the option that is already selected gives nothing (spec, haptics table). */
export function hapticSelectionIfChanged<T>(prev: T, next: T): void {
  if (!Object.is(prev, next)) hapticSelection();
}

/** A physical acknowledgement for a gesture that isn't a simple tap -- e.g. a long-press that is
 * about to open a destructive confirmation. Medium matches expo-haptics' own default style. */
export function hapticImpact(): void {
  fire('impact', () => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium));
}
