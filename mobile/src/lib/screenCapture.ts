import { usePreventScreenCapture as useExpoPreventScreenCapture } from 'expo-screen-capture';

/**
 * false blocks screenshots and screen recording on the screens that show balances and statements
 * (SEC-17, docs/quality/bug-reports/2026-08-19-security-review-findings.md). It was true for a while
 * so the product could be filmed (demos, bug reports); the live release needs it false. Set it to
 * true only to film a build, and set it back before release -- see
 * docs/engineering/mobile/android-release-checklist.md.
 */
export const ALLOW_SCREEN_CAPTURE = false;

const doNothing = () => {};

export function chooseScreenCaptureGuard(allowCapture: boolean, protect: () => void): () => void {
  return allowCapture ? doNothing : protect;
}

/** Every screen that blocks capture calls this instead of expo-screen-capture's hook directly, so
 *  ALLOW_SCREEN_CAPTURE is the one switch for all of them. */
export const usePreventScreenCapture = chooseScreenCaptureGuard(ALLOW_SCREEN_CAPTURE, useExpoPreventScreenCapture);
