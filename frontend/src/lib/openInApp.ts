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
 *    Being one static tag, it shows on every page, the one-time-token pages (NO_PROMPT_PATHS)
 *    included, and its "Open" starts the app on its home screen. Those links already open the app
 *    directly when it is installed (universal links), and the page in Safari keeps working.
 *
 * Android's banner is ON unless VITE_OPEN_IN_APP_ANDROID is "false" (the app is live on Google
 * Play; it was off by default until 2026-11-10, and the production bundle still compiled it off
 * that day). iPhone's tag is OFF until VITE_IOS_APP_STORE_ID is set: the iOS app is in App Store
 * review, and no App Store prompt should go up before it is live. Either is a Cloudflare Pages
 * build variable and a redeploy, the same convention as lib/features.ts. The landing page's footer
 * links both stores regardless of these switches.
 */

/** The production Android app's package (mobile/app.config.ts `android.package`). */
export const ANDROID_PACKAGE = 'com.fynora.android';

/** The production app's own URL scheme (mobile/app.config.ts `scheme`). Any `finora://` link opens
 *  the app on its home screen: the app's navigation has link prefixes but no per-path routes. */
export const APP_SCHEME = 'finora';

export const PLAY_STORE_URL = `https://play.google.com/store/apps/details?id=${ANDROID_PACKAGE}`;

/** The iOS app's App Store id: the digits in its App Store URL, the same value
 *  VITE_IOS_APP_STORE_ID takes for the Smart App Banner. */
export const APP_STORE_ID = '6809816093'; // synthetic-ok: a public App Store id (it is in the listing's URL), not customer data

/**
 * The iOS app's App Store page, in the India storefront. The storefront is in the path on purpose:
 * the country-less form (apps.apple.com/app/id...) and the US storefront both answer 404 for this
 * app (checked 2026-11-09), and the slug form is the one Apple's own links use. Apple's `?uo=4`
 * tracking tag is left off.
 */
export const APP_STORE_URL = `https://apps.apple.com/in/app/fynora-personal-finance/id${APP_STORE_ID}`;

/** Remembers, on this device, that the visitor closed the banner. */
export const DISMISSED_KEY = 'fynora.openInAppBannerDismissed';

/** Whether the Android banner is switched on: yes, unless VITE_OPEN_IN_APP_ANDROID is exactly
 *  "false" at build time (the off switch). Read on each call, not once at import, so a test can
 *  flip it with vi.stubEnv. */
export function androidOpenInAppEnabled(): boolean {
  return import.meta.env.VITE_OPEN_IN_APP_ANDROID !== 'false';
}

/**
 * Whether this is a Chromium-based browser on an Android device (Chrome, Samsung Internet, Edge,
 * Opera -- each says "Chrome/" in its user agent): the intent link below is Chrome's own format,
 * and a browser without it would show a button that does nothing, so Firefox is left out. Not an
 * Android app's embedded WebView ("; wv)"), where an intent would open a second app from inside
 * another one.
 */
export function isAndroidBrowser(userAgent: string): boolean {
  return /Android/i.test(userAgent) && /Chrome\//.test(userAgent) && !/;\s*wv\)/i.test(userAgent);
}

/**
 * Pages an emailed link opens with a one-time token in its address -- verifying an email or a phone,
 * resetting a password. The app would open on its home screen without that token, stranding the
 * person halfway through, so no "open the app" prompt there. (When the app is installed, those
 * links already open it directly: mobile/appLinks.config.js.)
 */
export const NO_PROMPT_PATHS = ['/reset-password', '/verify-email', '/email-change-verify', '/verify-phone'];

/**
 * The Chrome intent link the banner's button follows: opens {@link ANDROID_PACKAGE} through its
 * {@link APP_SCHEME}, or, when the app is not installed, `fallbackUrl` (the Play Store listing,
 * with the page's install referrer when the banner passes one: lib/playStoreLink.ts). Naming the
 * package means no other app can answer it. No host or path: the app's navigation has no per-path
 * routes, so a path would be read as a screen name it does not have; the bare scheme opens it at
 * home. The fallback is URL-encoded whole, so its own encoded referrer is encoded once more and
 * comes back intact when Chrome decodes the extra.
 */
export function androidOpenAppUrl(fallbackUrl: string = PLAY_STORE_URL): string {
  return `intent://#Intent;scheme=${APP_SCHEME};package=${ANDROID_PACKAGE};`
    + `S.browser_fallback_url=${encodeURIComponent(fallbackUrl)};end`;
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
