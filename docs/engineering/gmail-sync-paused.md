# Gmail sync is paused

Gmail sync (reading receipts from a user's Gmail to create transactions) is switched off, not removed.
All the code is still in the repository and still tested. This page says what "paused" means, how to
pause it in production, and how to switch it back on.

## Why

Gmail's read-only scope (`gmail.readonly`) is a Google "restricted scope". Google requires an annual
ADA-CASA security assessment by an approved lab for any app that uses one (their verification email
gave 20 December 2026 as the date). That assessment is not funded yet, so the feature is paused until it
is.

## What "paused" does

Two independent layers. The backend one is the real control; the app ones only decide what is drawn.

**Backend, one variable: `GMAIL_SYNC_ENABLED`** (on by default, so nothing changes until it is set to `false`).

| While `GMAIL_SYNC_ENABLED=false` | |
| --- | --- |
| Connect a mailbox | refused, 503 |
| The OAuth callback | refused, 503 (the user is sent back to Settings with `gmail=failed`) |
| Verify connection, Sync Now | refused, 503 (checked before the plan entitlement, since upgrading would not bring it back) |
| The background sync (`GmailDiscoveryWorker`) | does not run |
| `/gmail/status` | reports `available: false`, which is what the apps read |
| **Disconnect** | **still works**, on purpose: pausing must never remove a user's ability to revoke access to their own mailbox |
| Admin health card "Gmail Sync" | shows "Paused", not "Not configured" |
| Google client id / secret / redirect URI | untouched, so nothing has to be re-entered later |

`GMAIL_DISCOVERY_ENABLED`, the older variable that controls only the background job, still works on its
own; when it is set it wins over `GMAIL_SYNC_ENABLED` for the job.

**Web and mobile: `GMAIL_SYNC_UI_ENABLED`**, which decides whether the screens are drawn.

- Web: read from the build-time environment variable `VITE_GMAIL_SYNC_UI_ENABLED` in `frontend/src/lib/features.ts`.
  Unset, which is how it is deployed, means hidden; only the exact string `true` shows it. Same "unset means
  hidden" convention as `VITE_GOOGLE_LOGIN_CLIENT_ID`, and build-time like `VITE_SENTRY_DSN`, so a change needs a
  redeploy.
- Mobile: a plain constant, `false`, in `mobile/src/lib/features.ts`, the same shape as `ALLOW_SCREEN_CAPTURE`.

- Web: no Settings "Connected Apps" tab (it held only Gmail), no "Connect Gmail" dashboard shortcut, no
  "Gmail Sync" topic in Help (or in Help search), and no `/app/settings/gmail/review` route. An old
  `?tab=connected-apps` link falls back to General.
- Mobile: no Settings "Connected Apps" row. The Gmail connection card and the review screen were only reachable
  from there.
- The components, screens, API clients and their tests are all still in the code.

**Not changed:** the admin portal (trusted senders, merchant templates and the sample-email tools are internal
tooling), the Privacy Policy text, and the Account Aggregator ("Bank Sync"), which is a separate feature.

## Pausing it in production

1. Set `GMAIL_SYNC_ENABLED=false` on the backend service in Railway and let it restart. Do not remove
   `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET` or `GOOGLE_OAUTH_REDIRECT_URI`.
2. Check the admin health card "Gmail Sync" reads "Paused", and `GET /api/v1/integrations/google/gmail/status`
   returns `available: false`.
3. An installed mobile build that predates this change still draws the Settings row. It meets a backend that
   says the feature is unavailable, and its Gmail section shows "Gmail sync isn't available on this deployment
   yet" (`GmailConnectionSection.tsx`) rather than failing.

### Users who had already connected a mailbox

Pausing does not delete anything. A connection that existed stays in the database with its encrypted refresh
token, and simply nothing reads it. Google still lists the app under that user's account permissions until the
connection is disconnected, which revokes it. Whether to revoke every existing connection now, or leave them
dormant, is a decision for the owner; it has not been made here. To see how many exist:

```sql
SELECT status, count(*) FROM gmail_connections GROUP BY status;
```

Transactions already created from Gmail receipts are ordinary transactions and are not removed by any of this.

## Switching it back on

Do these in order. Do not start until the CASA assessment is complete and Google has approved the verification:
per `GoogleOAuthProperties`, production access beyond 100 test users needs both.

1. Backend: set `GMAIL_SYNC_ENABLED=true` (or delete the variable) and restart. Confirm the health card reads
   "Configured" and `/gmail/status` returns `available: true`.
2. Web: set `VITE_GMAIL_SYNC_UI_ENABLED=true` as a build environment variable on the user app's Cloudflare Pages
   project (Production), then redeploy. It is inlined at build time, so setting it alone does nothing. No code
   change and no web test change: the tests run with the variable unset, so they keep describing the paused default.
3. Mobile: set `GMAIL_SYNC_UI_ENABLED = true` in `mobile/src/lib/features.ts` and update
   `mobile/src/lib/features.test.ts` (it asserts the paused value on purpose), then ship a new build or OTA update.
4. Re-read the Privacy Policy's Gmail section and the Help answers against what the feature does by then.

## Tests that guard this

- Backend: `GoogleOAuthPropertiesTest`, `GmailConnectionServiceTest` (the "paused" group, including disconnect
  still working and connect working again once resumed), `GmailManualSyncServiceTest`,
  `GoogleOAuthControllerTest`, `GmailIntegrationHealthProviderTest`, and `GmailSyncSwitchConfigTest`, which loads
  the real `application.yml` to prove one variable stops the background job too.
- Web: `SettingsNav.test.tsx`, `Settings.test.tsx`, `Dashboard.test.tsx`, `Help.test.tsx`, `App.test.tsx`, and
  `lib/features.test.ts`, which reads the real flag: paused when the variable is unset, shown only for the exact
  value `true`, and paused for typos such as `TRUE`, `1` or ` true`.
- Mobile: `SettingsScreen.test.tsx` (mocks the flag to cover both states) and `lib/features.test.ts`, which reads
  the real constant and fails if someone flips it.
