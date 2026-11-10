import { AppState } from 'react-native';
import * as StoreReview from 'expo-store-review';
import { getCurrentAppAlert } from './appAlert';
import { isAuthenticating, isLocked, isSharing } from './appLock';
import { safeStorage } from './safeStorage';

/**
 * The one place the app asks for an App Store / Play Store rating. Every trigger (a successful
 * statement import, the third Fyn answer on a visit) calls this, so they share one rule about how
 * often a person is asked.
 *
 * Who is asked: everyone who reaches a trigger, the same way. Callers must never first ask whether
 * the person is happy and then only ask the happy ones -- that is review gating, which Google Play's
 * in-app review policy prohibits and Apple's guidelines discourage.
 *
 * How often: at most once per STORE_REVIEW_MIN_GAP_MS on this device. iOS and Android throttle the
 * native sheet on their own as well (iOS shows it at most three times a year; Play keeps an
 * undisclosed quota), and neither tells the app whether the sheet was actually shown. Our own gap
 * stops the triggers from spending that quota on back-to-back moments.
 *
 * Never rejects. Callers fire this with `void`, so a rejection would be an unhandled promise
 * rejection, which Sentry reports as a real error, for something that is only a nicety.
 */

export const STORE_REVIEW_LAST_ASKED_KEY = 'finora_store_review_last_asked';
export const STORE_REVIEW_MIN_GAP_MS = 90 * 24 * 60 * 60 * 1000;

/** Two triggers firing together (or one firing twice) must not both pass the gap check. */
let inFlight = false;

/**
 * The stored time, or null when there is none to honour: nothing stored, a value that is not a
 * time, or a time in the future (the phone's clock was moved back), which would otherwise block
 * asking until that future date plus the full gap.
 */
function readLastAsked(raw: string | null, now: number): number | null {
  if (raw === null) return null;
  const value = Number(raw);
  if (!Number.isFinite(value) || value <= 0 || value > now) return null;
  return value;
}

/**
 * Whether the person is looking at the app with nothing else in front of them. Not when the app is
 * in the background (expo-store-review's iOS module throws without presenting anything then), not
 * over the app-lock screen or a Face ID / fingerprint prompt, not while the share sheet is open,
 * and not on top of an in-app alert still waiting for an answer.
 */
function screenIsFree(): boolean {
  return AppState.currentState === 'active'
    && !isLocked()
    && !isAuthenticating()
    && !isSharing()
    && getCurrentAppAlert() === undefined;
}

export async function maybeAskForStoreReview(): Promise<void> {
  if (inFlight) return;
  inFlight = true;
  try {
    const now = Date.now();
    const lastAsked = readLastAsked(await safeStorage.getItem(STORE_REVIEW_LAST_ASKED_KEY), now);
    if (lastAsked !== null && now - lastAsked < STORE_REVIEW_MIN_GAP_MS) return;
    if (!screenIsFree()) return;
    // hasAction() covers both "does this platform have a review flow" and "is it available now"
    // (TestFlight has none). When it is false nothing was asked, so nothing is recorded.
    if (!(await StoreReview.hasAction())) return;
    await StoreReview.requestReview();
    // Recorded only once the request went through. Both native modules reject only when nothing
    // was presented (iOS: no foreground window; Android: the Play review flow failed), so a failed
    // request must not use up the next 90 days.
    await safeStorage.setItem(STORE_REVIEW_LAST_ASKED_KEY, String(now));
  } catch {
    // Best-effort only -- see this module's doc comment for why this must swallow, not throw.
  } finally {
    inFlight = false;
  }
}
