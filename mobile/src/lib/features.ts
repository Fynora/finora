/**
 * Gmail sync is PAUSED, not removed. While this is false the app shows no way to reach it: no
 * "Connected Apps" row in Settings, and therefore no Gmail connection card and no Gmail review
 * screen (both were only reachable from there). Every screen, hook and API call behind them is still
 * here and still tested; nothing was deleted.
 *
 * Why it is paused: Gmail's read-only scope is a Google "restricted scope", which requires an annual
 * ADA-CASA security assessment by an approved lab. That is not funded yet.
 *
 * Turning it back on is a three-part switch, and the full checklist is in
 * docs/engineering/gmail-sync-paused.md:
 *   1. backend: set GMAIL_SYNC_ENABLED=true (or remove it) and restart;
 *   2. web: set VITE_GMAIL_SYNC_UI_ENABLED=true on Cloudflare Pages and redeploy (an environment
 *      variable there, not a constant; see frontend/src/lib/features.ts);
 *   3. here: set this to true, then ship a new build or OTA update.
 *
 * A plain constant, the same shape as ALLOW_SCREEN_CAPTURE in screenCapture.ts, so the state of the
 * feature is visible in git. The backend switch is the real control (it stops Gmail access and the
 * background sync, and an older installed build that still shows the screens just meets a backend
 * that reports the feature unavailable); this only decides whether the screens are drawn.
 */
export const GMAIL_SYNC_UI_ENABLED = false;

/**
 * Bank Sync (Account Aggregator) is NOT offered yet. While this is false, the Bank Sync screen still
 * lists any links a person already has (so they can see and disconnect them), but the connect button
 * reads "Coming soon" and does nothing. Nothing behind it was removed.
 *
 * A plain constant like GMAIL_SYNC_UI_ENABLED above. The web app reads VITE_BANK_SYNC_CONNECT_ENABLED
 * (see frontend/src/lib/features.ts); open both together, then ship a new build or OTA update.
 */
export const BANK_SYNC_CONNECT_ENABLED = false;
