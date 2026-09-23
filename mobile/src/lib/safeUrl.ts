/**
 * Whether `url` is safe to hand to Linking.openURL() -- i.e. an absolute http(s) URL rather than a
 * scheme that could trigger something other than opening a web page (a `javascript:`/`data:` URL,
 * or on Android an `intent://` URL, which can be crafted to launch an arbitrary activity -- the
 * "Android Intent Scheme" attack class).
 *
 * Guards SettingsBankSyncScreen's account-aggregator redirectUrl: the app's own backend returns it
 * (relaying Setu's response), and the screen used to open it unconditionally, with no scheme check
 * at all. The value isn't attacker-entered on the happy path, so this is defense-in-depth rather
 * than closing a demonstrated live exploit -- the same reasoning the admin portal already applies
 * to Bank.websiteUrl (see admin-portal/src/lib/safeUrl.ts): a value that reaches this call through
 * any other route -- a backend bug, a misbehaving third party, a future caller that reuses this
 * field without the same trust assumption -- still can't make the app open anything but a plain web
 * page.
 *
 * Deliberately not the URL constructor: whether Hermes provides one without the polyfill this repo
 * doesn't have is unverified (see appLinks.ts's own doc comment on the identical tradeoff for deep
 * links), so this checks the scheme with a regex instead, anchored to the start of the string.
 */
export function isSafeExternalUrl(url: string | null | undefined): boolean {
  if (!url) return false;
  return /^https?:\/\//i.test(url);
}
