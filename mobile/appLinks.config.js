// PLAIN JAVASCRIPT, DELIBERATELY -- same reason as plugins/withRNFirebaseDisableSPM.js: Expo's config
// loader evaluates app.config.ts on its own and cannot resolve a sibling .ts file ("Cannot find
// module './src/lib/appLinks'", reproduced), so anything app.config.ts imports has to be .js.
// Types live in appLinks.config.d.ts; src/lib/appLinks.ts re-exports these for app code.

/**
 * Hosts whose https links the app claims (iOS Associated Domains + Android App Links). The web app
 * is deployed at exactly these origins; the backend builds every emailed link from APP_BASE_URL,
 * which is the production one (see EmailProperties.resolveBaseUrl).
 */
const APP_LINK_HOSTS = {
  production: 'app.fynora.net',
  development: 'dev-app.fynora.net',
};

/**
 * Paths the OS should hand to the app instead of the browser. Each one is a page an email links to
 * AND has real handling in the app -- claiming a path with no handler would land the user on an app
 * screen that ignores the link. Two are deliberately absent:
 *  - `/reset-password`: completing a reset needs a Firebase phone-OTP step that only exists on the
 *    web page today, so that link stays web.
 *  - `/app/billing`: the app itself sends people there in a browser (MySubscriptionScreen's
 *    "Manage on web" -- the only way to change or cancel a web-purchased plan). On Android an app
 *    that opens a link it has verified for itself is answered by itself, so claiming it would make
 *    that button reopen the app instead of reaching the web page. Billing emails stay web too.
 *    A test (appLinks.selfOpen.test.ts) fails if any path the app opens in a browser is claimed.
 *
 * Matched as prefixes (Android `pathPrefix`; the iOS file uses the equivalent `path/*` form), so
 * `/app/imports` also covers `/app/imports/<jobId>`.
 */
const APP_LINK_PATH_PREFIXES = [
  '/verify-email',
  '/email-change-verify',
  '/verify-phone',
  '/register',
  '/app/settings',
  '/app/imports',
];

module.exports = { APP_LINK_HOSTS, APP_LINK_PATH_PREFIXES };
