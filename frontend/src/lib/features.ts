/**
 * Gmail sync is PAUSED, not removed. While this is false the web app shows no way to reach it: no
 * "Connected Apps" tab in Settings, no "Connect Gmail" shortcut on the dashboard, no Gmail answers in
 * Help, and no review page route. Every component behind those (ConnectedAppsPane, GmailReview, the
 * `gmailApi` client) is still here and still tested; nothing was deleted.
 *
 * Why it is paused: Gmail's read-only scope is a Google "restricted scope", which requires an annual
 * ADA-CASA security assessment by an approved lab. That is not funded yet.
 *
 * Off unless `VITE_GMAIL_SYNC_UI_ENABLED` is exactly "true" at BUILD time -- the same "unset means
 * hidden" convention as `VITE_GOOGLE_LOGIN_CLIENT_ID`, and unset is the state Cloudflare builds in
 * today. Vite inlines it when it builds, so setting it on Cloudflare Pages does nothing until the next
 * deploy. Read from the environment rather than written as `= false` so that it is honest about being
 * a deployment switch, and so switching it back on needs no code change.
 *
 * Turning it back on is a three-part switch, in this order, and the full checklist is in
 * docs/engineering/gmail-sync-paused.md:
 *   1. backend: set GMAIL_SYNC_ENABLED=true (or remove it) and restart;
 *   2. mobile: set GMAIL_SYNC_UI_ENABLED to true in mobile/src/lib/features.ts (a constant there);
 *   3. here: set VITE_GMAIL_SYNC_UI_ENABLED=true on Cloudflare Pages and redeploy.
 *
 * The backend switch is the real control (it stops Gmail access and the background sync); this only
 * decides whether the screens are drawn.
 */
export const GMAIL_SYNC_UI_ENABLED = import.meta.env.VITE_GMAIL_SYNC_UI_ENABLED === 'true';
