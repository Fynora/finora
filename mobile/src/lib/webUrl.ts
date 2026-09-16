import { Linking } from 'react-native';
import { reportHandledError } from './monitoring';

// The web app's own absolute origin -- distinct from EXPO_PUBLIC_API_BASE_URL (the backend API).
// Fynora's Privacy Policy and Terms of Service (frontend/src/pages/Privacy.tsx, Terms.tsx) are
// marketing/legal routes on the web app, not the backend; mobile has no in-app copies of them and
// links out to these instead, the same way the backend's own app-base-url is what powers the web
// links it emails (see ForgotPasswordScreen's doc comment on APP_BASE_URL). Falls back to
// production rather than throwing like client.ts's API base does -- a wrong/missing value here
// just opens the wrong environment's copy of a static page, not a broken app.
const APP_BASE_URL = (process.env.EXPO_PUBLIC_APP_BASE_URL || 'https://app.fynora.net').replace(/\/+$/, '');

export function webUrl(path: string): string {
  return `${APP_BASE_URL}${path.startsWith('/') ? path : `/${path}`}`;
}

/**
 * Bug fix: every call site that opened one of these web pages did `Linking.openURL(webUrl(path))`
 * directly, from a bare `onPress`, with no `.catch()` -- so any rejection (seen live via Sentry: a
 * real iPhone SE on iOS 27 failing to open BOTH /trust and /your-data within the same session,
 * `mechanism: onunhandledrejection`) became an unhandled promise rejection reported as a crash,
 * not a handled failure. `Linking.openURL()` rejecting for a plain https URL on a real device with
 * Safari installed has no established root cause here (canOpenURL() is a known-unreliable guard
 * for this -- see react-native#34037 -- so it isn't used as one); this closes the actual gap that
 * turned "the link didn't open" into a reported crash regardless of that cause. Matches the
 * try/catch ReferralsScreen.handleChannel already carries for its own Linking.openURL() call.
 */
export function openWebUrl(path: string): void {
  Linking.openURL(webUrl(path)).catch((err: unknown) => {
    reportHandledError(err, 'open-web-url');
  });
}
