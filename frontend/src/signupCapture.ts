/**
 * The early copy of the sign-up capture: the entry the build bundles into its own small classic
 * script, which every prerendered public page loads (scripts/prerender.mjs, withEarlyCapture).
 *
 * Why it exists. The app's main bundle is large and loads as a deferred module, so on a slow
 * connection a prerendered page is on screen, with working <a href> links, well before main.tsx
 * runs. A visitor who clicks one of those links in that window starts a second full page load, and
 * the first page's capture never ran: the referrer and campaign tags are gone and the second page
 * would count as the landing page. This script is a few hundred bytes of the same
 * captureSignupAttribution, fetched at the head of the page and executed (deferred, in document
 * order) before the module bundle, so the capture is done by the time the page can be interacted
 * with in any practical sense.
 *
 * Running it twice is safe by construction: main.tsx still calls captureSignupAttribution, and on
 * the same page load the second call writes the same value (a tagged arrival) or finds the tab
 * already holding a capture and does nothing (everything else). The main.tsx call remains for every
 * page that does not carry this script: the blank shell for /auth and /app, the 404 page, and the
 * local dev servers.
 *
 * Nothing but the capture is imported, so the bundle stays tiny and runs without React.
 */
import { captureSignupAttribution } from './lib/signupAttribution';

captureSignupAttribution(window);
