import { usePreventScreenCapture as useExpoPreventScreenCapture } from 'expo-screen-capture';

/**
 * TEMPORARY, until the live release: true lets screenshots and screen recording through on every
 * screen, so the product can be filmed (demos, bug reports). It must go back to false before the app
 * goes live -- SEC-17 (docs/quality/bug-reports/2026-08-19-security-review-findings.md) blocks capture
 * on the screens that show balances and statements. The re-enable step is in
 * docs/engineering/mobile/android-release-checklist.md.
 */
export const ALLOW_SCREEN_CAPTURE = true;

const doNothing = () => {};

export function chooseScreenCaptureGuard(allowCapture: boolean, protect: () => void): () => void {
  return allowCapture ? doNothing : protect;
}

/** Every screen that blocks capture calls this instead of expo-screen-capture's hook directly, so
 *  ALLOW_SCREEN_CAPTURE is the one switch for all of them. */
export const usePreventScreenCapture = chooseScreenCaptureGuard(ALLOW_SCREEN_CAPTURE, useExpoPreventScreenCapture);
