import { Alert, Linking } from 'react-native';
import * as Clipboard from 'expo-clipboard';
import * as WebBrowser from 'expo-web-browser';
import { reportHandledError, reportHandledEvent } from './monitoring';

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
 *
 * Second bug fix (review): stopping the crash isn't enough on its own -- a caller with no error UI
 * of its own (LegalFooterLinks, SettingsScreen's Legal rows, RegisterScreen's Terms/Privacy text)
 * previously left the user with a silent dead tap on failure: the same rejection, just swallowed
 * one level up instead of crashing. Alert.alert here gives every caller of this shared helper real
 * recoverability, not just an error state. A plain text URL in the message isn't actually
 * actionable on a phone -- nobody retypes a URL from memory of a popup -- so "Copy Link" puts it
 * on the clipboard via expo-clipboard (already a dependency, same API ReferralsScreen.tsx's own
 * share flow already uses) so the user can paste it into any browser themselves.
 * MySubscriptionScreen's two Linking.openURL calls don't go through this helper for the same
 * reason as before: that screen already has its own visible error UI (setError, matching
 * pause/resume) and a plain Alert here would just duplicate it.
 *
 * Third change: when the system refuses to open the link, it is opened in an in-app browser
 * instead, and the Copy Link alert only appears if that fails too. See the comments inside.
 */
export function openWebUrl(path: string): void {
  const url = webUrl(path);
  Linking.openURL(url).catch((err: unknown) => {
    // Second attempt: an in-app browser sheet (SFSafariViewController / Custom Tabs) instead of
    // handing the URL to the system. Linking.openURL() rejected for plain https links on iOS 27.0,
    // across several different iPhones and builds (FYNORA-MOBILE-3); why is still not established,
    // so this is a workaround, not a diagnosis. expo-web-browser is already in the native build
    // (Gmail sign-in uses it), so this needs no new build.
    //
    // When the fallback works nothing is wrong for the person, so it is recorded as an info event
    // (still counted, so the failure rate stays visible) rather than as an error that would keep
    // the issue open and growing. Only if the fallback fails too is it reported as an error.
    reportHandledEvent('openURL rejected; opening in the in-app browser instead', 'open-web-url-fallback-used');
    WebBrowser.openBrowserAsync(url).catch((fallbackErr: unknown) => {
      reportHandledError(err, 'open-web-url');
      reportHandledError(fallbackErr, 'open-web-url-fallback');
      Alert.alert('Could not open this page', 'Try again, or copy the link and open it in a browser.', [
        { text: 'Copy Link', onPress: () => void Clipboard.setStringAsync(url) },
        { text: 'OK', style: 'cancel' },
      ]);
    });
  });
}
