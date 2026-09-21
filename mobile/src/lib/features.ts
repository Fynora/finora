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
 *   2. web: set GMAIL_SYNC_UI_ENABLED to true in frontend/src/lib/features.ts;
 *   3. here: set this to true, then ship a new build or OTA update.
 *
 * A plain constant, the same shape as ALLOW_SCREEN_CAPTURE in screenCapture.ts, so the state of the
 * feature is visible in git. The backend switch is the real control (it stops Gmail access and the
 * background sync, and an older installed build that still shows the screens just meets a backend
 * that reports the feature unavailable); this only decides whether the screens are drawn.
 */
export const GMAIL_SYNC_UI_ENABLED = false;
