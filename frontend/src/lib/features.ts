/**
 * Gmail sync is PAUSED, not removed. While this is false the web app shows no way to reach it: no
 * "Connected Apps" tab in Settings, no "Connect Gmail" shortcut on the dashboard, no Gmail answers in
 * Help, and no review page route. Every component behind those (ConnectedAppsPane, GmailReview, the
 * `gmailApi` client) is still here and still tested; nothing was deleted.
 *
 * Why it is paused: Gmail's read-only scope is a Google "restricted scope", which requires an annual
 * ADA-CASA security assessment by an approved lab. That is not funded yet.
 *
 * Turning it back on is a three-part switch, in this order, and the full checklist is in
 * docs/engineering/gmail-sync-paused.md:
 *   1. backend: set GMAIL_SYNC_ENABLED=true (or remove it) and restart;
 *   2. mobile: set GMAIL_SYNC_UI_ENABLED to true in mobile/src/lib/features.ts;
 *   3. here: set this to true.
 *
 * A plain constant rather than a runtime setting, on purpose: the backend switch is the real control
 * (it stops Gmail access and the background sync), and this only decides whether the screens are
 * drawn, which for a paused feature should be visible in git and impossible to flip by accident.
 */
export const GMAIL_SYNC_UI_ENABLED = false;
