/**
 * "Open in the Fynora app" for a visitor on a phone browser. A website cannot tell whether the app
 * is installed (browsers hide that), so each platform uses what it offers:
 *
 *  - Android: our own banner (OpenInAppBanner) whose button is a Chrome intent link. Chrome opens
 *    the installed app; when it is not installed, Chrome goes to the fallback URL, the Play Store
 *    listing, instead.
 *  - iPhone: Apple's Smart App Banner, a <meta> tag Safari reads at page load, which shows "Open"
 *    when the app is installed and "Get" when not. It is added to index.html at BUILD time by
 *    vite.config.ts, only when VITE_IOS_APP_STORE_ID is set -- see iosSmartAppBannerTag below.
 *
 * Both are OFF in the shipped state: the apps are not on the stores yet, and a banner shown now
 * would send people to an app they cannot install. Turning either on is a Cloudflare Pages build
 * variable and a redeploy, the same convention as lib/features.ts.
 */

/** The production Android app's package (mobile/app.config.ts `android.package`). */
export const ANDROID_PACKAGE = 'com.fynora.android';

/** The production app's own URL scheme (mobile/app.config.ts `scheme`). Any `finora://` link opens
 *  the app on its home screen: the app's navigation has link prefixes but no per-path routes. */
export const APP_SCHEME = 'finora';

export const PLAY_STORE_URL = `https://play.google.com/store/apps/details?id=${ANDROID_PACKAGE}`;

/** Remembers, on this device, that the visitor closed the banner. */
export const DISMISSED_KEY = 'fynora.openInAppBannerDismissed';

/** Whether the Android banner is switched on: only the exact string "true" in
 *  VITE_OPEN_IN_APP_ANDROID, at build time. Read on each call, not once at import, so a test can
 *  flip it with vi.stubEnv. */
export function androidOpenInAppEnabled(): boolean {
  return import.meta.env.VITE_OPEN_IN_APP_ANDROID === 'true';
}

/**
 * Whether this is a browser on an Android device -- not an Android app's embedded WebView ("; wv)"
 * in its user agent), where an intent link would open a second app from inside another one.
 */
export function isAndroidBrowser(userAgent: string): boolean {
  return /Android/i.test(userAgent) && !/;\s*wv\)/i.test(userAgent);
}

/**
 * The Chrome intent link the banner's button follows: opens {@link ANDROID_PACKAGE} through its
 * {@link APP_SCHEME}, or, when the app is not installed, the Play Store listing. Naming the package
 * means no other app can answer it. No host or path: the app's navigation has no per-path routes,
 * so a path would be read as a screen name it does not have; the bare scheme opens it at home.
 */
export function androidOpenAppUrl(): string {
  return `intent://#Intent;scheme=${APP_SCHEME};package=${ANDROID_PACKAGE};`
    + `S.browser_fallback_url=${encodeURIComponent(PLAY_STORE_URL)};end`;
}

/**
 * The Smart App Banner tag for index.html, or null when there is no App Store id to point it at.
 * The id is the number in the app's App Store URL (".../id" then digits); anything else is ignored
 * rather than shipped as a broken tag.
 */
export function iosSmartAppBannerTag(appStoreId: string | undefined): string | null {
  const id = appStoreId?.trim();
  if (!id || !/^\d{6,12}$/.test(id)) return null;
  return `<meta name="apple-itunes-app" content="app-id=${id}" />`;
}
