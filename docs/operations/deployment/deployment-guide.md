# Deployment Guide

Covers running Finora's backend and both frontends (`frontend/`, the user app; `admin-portal/`,
the admin app) locally, in Docker, on Railway (backend + Postgres), and on Cloudflare (Pages or Workers)
(both frontends). Written as part of the production-readiness pass moving Finora from a purely
local setup to a real cloud deployment (Railway + Cloudflare).

The goal this documents: the same backend codebase runs unmodified locally, in Docker, on
Railway, or on any other cloud provider (AWS/Azure/GCP) — every environment-specific value comes
from an environment variable, never a hardcoded value in source.

## Contents

1. [Environment variable audit (backend)](#environment-variable-audit-backend)
2. [Local development](#local-development)
3. [Docker (docker-compose)](#docker-docker-compose)
4. [Railway (backend + Postgres)](#railway-backend--postgres)
5. [Before running more than one backend instance](#before-running-more-than-one-backend-instance)
6. [Cloudflare (both frontends)](#cloudflare-both-frontends)
7. [Dev environment (admin-portal, frontend, mobile)](#dev-environment-admin-portal-frontend-mobile)
8. [Search engines: which host is indexed](#search-engines-which-host-is-indexed)
9. [Frontend environment variables](#frontend-environment-variables)

---

## Environment variable audit (backend)

Every environment variable the backend reads anywhere, extracted directly from
`application.yml`/`application-*.yml` (the single source of truth — nothing in the Java code
reads an env var that isn't routed through one of these files). "Safe for production" means: safe
to leave at its default: local dev defaults, or must be explicitly set.

| Variable | Required in prod? | Default (dev/local only) | Used for | Safe to leave at default in prod? |
|---|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Yes | `dev` | Selects which `application-*.yml` overlay applies | **No** — must be `prod` |
| `PORT` | Set automatically by Railway | `8080` | Which port the app listens on (`server.port`) | Yes — Railway sets this itself |
| `DB_HOST` | Yes | `localhost` | Postgres host | No |
| `DB_PORT` | Yes | `5432` | Postgres port | Usually fine as-is |
| `DB_NAME` | Yes | `finora` | Postgres database name | No |
| `DB_USER` | Yes | `finora` | Postgres username | No |
| `DB_PASSWORD` | Yes | `finora` | Postgres password | **No — never leave this default in prod.** `ProductionConfigValidator` now refuses to start the `prod` profile if this is still `finora`. |
| `DB_POOL_MAX_SIZE` | No | `10` | HikariCP max connections | Check against your Postgres plan's own connection ceiling before relying on the default |
| `DB_POOL_MIN_IDLE` | No | `2` | HikariCP min idle connections | Yes |
| `JWT_SECRET` | Yes | placeholder string | Signs/verifies access + refresh tokens (HS256, needs 32+ chars) | **No — never leave this default in prod.** `ProductionConfigValidator` refuses to start if this is unset, still the placeholder, or under 32 characters. |
| `JWT_EXPIRATION_MS` | No | `900000` (15 min) | Access token lifetime | Yes |
| `JWT_REFRESH_EXPIRATION_MS` | No | `2592000000` (30 days) | Refresh token lifetime | Yes |
| `JWT_REFRESH_REUSE_GRACE_MS` | No | `30000` (30 s) | How long after a refresh token was rotated the same token may be presented again and still get a fresh pair for the same session (a retried request, two app instances) instead of being read as theft. `0` restores the strict rule. See `application.yml` for the trade-off. | Yes |
| `CORS_ORIGINS` | Yes | both local dev ports | Comma-separated allowed origins (`CorsConfig`) | **No** — must list your real deployed frontend origin(s), no wildcard |
| `APP_BASE_URL` | Yes | `http://localhost:5173` | Base URL used to build links in emails (password reset, etc. — see `EmailConfig`) | **No** — must be your real deployed frontend's URL, or generated links point at localhost |
| `ADMIN_APP_BASE_URL` | Recommended | `http://localhost:5174` | Same purpose as `APP_BASE_URL`, but for the admin portal specifically — see `EmailProperties.resolveBaseUrl()`. The user frontend and admin portal are separate deployed apps at separate origins, each with its own `/reset-password` page, but there's no separate admin auth service; without this set, an admin's "Forgot Password" links to the *user* app's reset page instead of the admin portal's own. Picked automatically from the request's `Origin` header — no frontend changes needed either way. | Leave unset only if you're fine with admin password resets linking to the wrong app |
| `RESEND_API_KEY` | **Yes — hard boot-time requirement in `prod`** | empty | Resend API key; empty falls back to `NoOpEmailService` (logs the link instead of sending) | **No.** Unset, this used to just mean silent no-op emails; `ProductionConfigValidator` now refuses to *start* the `prod` profile at all if this is blank, because the actual failure mode is worse than "no email" — `NoOpEmailService.isConfigured()` returning `false` makes `AuthService.forgotPassword()` return the raw, valid reset link directly in the API response instead, a full account-takeover primitive for anyone who knows a user's email address. |
| `EMAIL_FROM` | No | `onboarding@resend.dev`<!-- synthetic-ok: Resend's own published sandbox sender address, not customer data --> | "From" address for outgoing email | Only if you have your own verified sender — Resend requires the sending domain to be verified (SPF/DKIM DNS records added in Resend's dashboard) before it will actually send as that address; until verified, sends silently fail or land in spam rather than erroring at boot |
| `EMAIL_FROM_NAME` | No | unset | Optional display name shown alongside `EMAIL_FROM` (`app.email.from-name` — see `EmailProperties`) — e.g. set to `Finora` so outgoing mail shows as `Finora <noreply@yourdomain>` instead of the bare address | Cosmetic only; leaving it unset is safe, just less polished in the recipient's inbox |
| `GOOGLE_APPLICATION_CREDENTIALS` | **Yes — hard boot-time requirement in `prod`** | unset | Absolute path to a Firebase service-account JSON key file; read directly by the Firebase Admin SDK (`FirebaseConfig`), not via a Spring `@ConfigurationProperties` binding — there's no `application.yml` key for this | **No.** `ProductionConfigValidator` refuses to start the `prod` profile unless `PhoneVerificationProvider.isConfigured()` (i.e. this file is present and valid) — see its own doc comment. Phone verification (registration, password reset, authenticated password change) fails with a 503 everywhere else if this is missing. |
| *(unset)* | — | — | `FirebaseConfig.firebaseApp()` logs a warning and yields `Optional.empty()` — the app still starts (outside `prod`), but any phone-verification-gated flow fails until this is set | Acceptable only outside `prod` |
| `GOOGLE_APPLICATION_CREDENTIALS_BASE64` | Only if you use this instead of a mounted/volume file | unset | Railway-friendly alternative to `GOOGLE_APPLICATION_CREDENTIALS`: the entire Firebase service-account JSON key, base64-encoded, as one plain-string variable (Railway's Variables tab stores strings, not files). `backend/docker-entrypoint.sh` decodes it to `/app/firebase-service-account.json` and exports `GOOGLE_APPLICATION_CREDENTIALS` pointing at that file *before* the JVM starts — only when `GOOGLE_APPLICATION_CREDENTIALS` isn't already set directly, so this never overwrites an explicit value. | Same requirement as `GOOGLE_APPLICATION_CREDENTIALS` above — set one or the other |
| `GMAIL_SYNC_ENABLED` | No | `true` | Pause switch for the whole Gmail data integration (not "Sign in with Google"). Set `false` to pause: connect, the OAuth callback, verify, Sync Now and the background sync all stop, `/gmail/status` reports `available: false`, and disconnect still works. Leaves `GOOGLE_OAUTH_CLIENT_ID`/`_SECRET`/`_REDIRECT_URI` alone. Gmail sync is paused until Google's annual CASA assessment is funded — see `docs/engineering/gmail-sync-paused.md`. | Yes — on by default, so leaving it unset changes nothing. |
| `GOOGLE_LOGIN_CLIENT_IDS` | No | empty | D-23 "Sign in with Google" — comma-separated OAuth client id(s) `GoogleIdTokenVerifierService` accepts as a valid audience. A separate registration from `GOOGLE_APPLICATION_CREDENTIALS`/Gmail-sync OAuth above — see `GoogleLoginProperties`'s own doc comment for why. | Yes — unconfigured is a supported state: `POST /api/v1/auth/google` answers 503 and the frontend hides the Google button (see `VITE_GOOGLE_LOGIN_CLIENT_ID` below) entirely; nothing else in the app is affected. |
| `TWO_FACTOR_API_KEY` | No — soft, non-fatal check only | empty | 2Factor API key for real-time transaction alert SMS (`TwoFactorSmsProvider`) — scoped to `TransactionService.create()`'s manual-entry path only, never authentication OTPs (Firebase Phone Authentication owns those). Empty falls back to `NoOpSmsProvider` (logs instead of sending). | **Yes.** Unlike `RESEND_API_KEY`/`GOOGLE_APPLICATION_CREDENTIALS`, `ProductionConfigValidator` only logs a startup warning if this is unset — never refuses to boot — since a missed transaction alert is a degraded notification, not a security gap (see `SmsProperties`'s own doc comment). |
| `FINORA_SETUP_KEY` | No | empty (auto-generated + written to `.finora/installation.key`) | First-run bootstrap installation key | See `docs/bootstrap-setup-future-work.md` — set explicitly rather than relying on a written file in any deployment without a persistent, host-readable filesystem |
| `ADMIN_MFA_ENABLED` / `ADMIN_MFA_ENFORCED` | **Yes, in prod** | `false` / `false` | Admin-portal two-factor authentication (`AdminMfaService`, `AdminMfaEnrollmentFilter`). `ENABLED` turns the feature on; `ENFORCED` refuses admin-portal requests from an account that has not enrolled. See `docs/security/admin-mfa-recovery.md` for the rollout order. | **No.** With both off, a phished admin password is full access to every user's financial data. `ProductionConfigValidator` logs a warning in `prod` when `ENFORCED` is not `true`; it does not refuse to boot, because enforcement must follow enrolment. |
| `SENTRY_DSN` / `SENTRY_ENVIRONMENT` | **Yes, in prod** | empty / `development` | Backend error reporting (`sentry.dsn` in `application.yml`; `send-default-pii` is off). Distinct from `VITE_SENTRY_DSN` below, which is the two SPAs' own DSN. | **No** — unset, backend exceptions are visible only in Railway's log stream, and nothing pages anyone. Set `SENTRY_ENVIRONMENT=prod` alongside it so events are filterable. |
| `FINORA_BOOTSTRAP_ENABLED` | Recommended `false` once setup is done | `true` | Whether the first-boot bootstrap account may be created (`SetupService`). Its one-time password is written in plaintext to `.finora/installation.key` inside the container until setup completes. | Set `false` after the first SUPER_ADMIN exists; the account is suspended at that point anyway, this just removes the file-writing path. |
| `MALWARE_SCAN_PROVIDER` | **Yes, in prod** | `none` | Which scanner every upload (statement import, admin analysis, support attachments, Fyn screenshots) goes through before a parser sees it: `none` or `clamav`. See "Malware scanning" under the Railway section. | **No** — at `none` uploads are unscanned and `ProductionConfigValidator` warns at boot. |
| `CLAMAV_HOST` / `CLAMAV_PORT` | With `clamav` | `localhost` / `3310` | Where clamd listens. On Railway: the ClamAV service's private-network hostname. | Only when the provider is `clamav`. |
| `MALWARE_SCAN_ON_UNAVAILABLE` | No | `reject` | What an upload gets when the scanner is configured but cannot answer: `reject` (503, error-level log) or `allow` (passes unscanned, warning). | Yes — `reject` is the safe default. |
| `CLAMAV_CONNECT_TIMEOUT_MS` / `CLAMAV_READ_TIMEOUT_MS` | No | `2000` / `30000` | Socket timeouts for one scan. | Yes |
| `IMPORT_QUEUE_ENABLED` (`app.import.queue.enabled`) | No | `false` | Whether statement uploads go through the async `ImportJobWorker` (claim locking, retries, dead-letter alerts) or are parsed on the request thread. | The async path is built and tested but not yet rolled out; leave `false` until the queue-activation rollout in the import reliability plan is done. |
| `RATE_LIMIT_AUTH_GLOBAL_MAX` / `_WINDOW_SECONDS` | No | `600` / `60` | Shared ceiling across ALL clients for the bcrypt-cost auth routes (login, register, email OTP request/login, MFA verify) — the per-IP limits bound one client, this bounds the instance's password-hashing budget against many. | Yes for one instance; scale it with instance size, not with user count. |
| `TRUST_PROXY_HEADERS` | **Yes, on Railway** | `false` | Whether `RateLimitFilter` trusts `X-Forwarded-For` for the real client IP | **Must be `true` on Railway** (or any deployment behind a real reverse proxy) — otherwise every user shares one rate-limit bucket. Must stay `false` anywhere not behind a trusted proxy, or rate limiting can be bypassed by spoofing the header. Like `TWO_FACTOR_API_KEY` above, `ProductionConfigValidator` only logs a startup warning if this is left at its default in `prod` — it never refuses to boot over this one. |
| `PASSWORD_CHANGE_SESSION_EXPIRY_MINUTES` | No | `15` | How long a started Change Password flow (`PasswordChangeSession`) stays usable before `verify-otp`/`complete` start rejecting it | Yes |
| `IMPORT_MAX_CONCURRENT` | No | `6` | Max concurrent statement-import requests (`ImportConcurrencyLimiter`), deliberately conservative relative to `DB_POOL_MAX_SIZE` so imports can't starve every other endpoint's DB usage. BH-043: past this limit, requests are rejected immediately (HTTP 503, `IMPORT_006`) rather than queued -- there is no wait-timeout variable to set anymore | Yes |
| `UPLOAD_MAX_FILE_SIZE` / `UPLOAD_MAX_REQUEST_SIZE` | No | `10MB` | Multipart upload size limits (CSV/PDF statement import) | Yes |

## Local development

```bash
cd backend
mvn spring-boot:run   # SPRING_PROFILES_ACTIVE defaults to dev, connects to localhost:5432
```

Requires a local Postgres running with the `finora`/`finora`/`finora` db/user/password (or set
`DB_*` env vars to point elsewhere). Frontends run via their own Vite dev servers (`npm run dev`
in `frontend/` and `admin-portal/`), each proxying `/api` to `localhost:8080` — see each app's
`vite.config.ts`. Neither frontend needs any env var set for local dev; `VITE_API_BASE_URL` /
`VITE_BACKEND_ORIGIN` are only relevant once deployed (see below).

**Without `GOOGLE_APPLICATION_CREDENTIALS` set, you can't get past registration.** Phone
verification is enforced server-side (`PhoneVerificationFilter`) before any account reaches the
dashboard, and every verification call 503s until the Firebase Admin SDK is configured
(`FirebaseConfig`). See the `GOOGLE_APPLICATION_CREDENTIALS` / `GOOGLE_APPLICATION_CREDENTIALS_BASE64`
rows in the [environment variable audit](#environment-variable-audit-backend) above, plus the six
`VITE_FIREBASE_*` vars in [Frontend environment variables](#frontend-environment-variables), for
what a fresh clone needs before phone verification actually works.

## Docker (docker-compose)

```bash
docker compose up --build -d
```

`docker-compose.yml` already sets every required backend env var for a self-contained local stack
(Postgres + backend, `dev` profile). Nothing here reflects real production values — the JWT
secret and DB password in `docker-compose.yml` are dev-only placeholders, same as
`application.yml`'s own defaults.

## Railway (backend + Postgres)

Railway auto-deploys the backend from `backend/Dockerfile` on every push to `main`.
`backend/railway.json` tells Railway to health-check `/actuator/health` rather than just checking
that the process started.

**Required Railway environment variables** (set these in the Railway service's Variables tab —
none of them belong in source control):

```
SPRING_PROFILES_ACTIVE=prod
DB_HOST=<Railway Postgres internal host>
DB_PORT=<Railway Postgres port>
DB_NAME=<Railway Postgres database name>
DB_USER=<Railway Postgres username>
DB_PASSWORD=<Railway Postgres password>
JWT_SECRET=<a real random 32+ char value — see "Generating JWT_SECRET" below; never reuse an example>
CORS_ORIGINS=https://app.fynora.net,https://admin.fynora.net
APP_BASE_URL=https://app.fynora.net
ADMIN_APP_BASE_URL=https://admin.fynora.net
RESEND_API_KEY=<your real Resend API key>
EMAIL_FROM=noreply@fynora.net
EMAIL_FROM_NAME=Fynora
# Either a mounted file path directly, or GOOGLE_APPLICATION_CREDENTIALS_BASE64 instead (see below
# and the environment variable audit table above) -- Railway's Variables tab only stores strings.
GOOGLE_APPLICATION_CREDENTIALS=/path/to/firebase-service-account.json
TRUST_PROXY_HEADERS=true
TWO_FACTOR_API_KEY=<your real 2Factor API key>
```

`GOOGLE_APPLICATION_CREDENTIALS` must point at a real, readable file on the deployed instance —
on Railway that typically means a "Raw file" volume mount, or setting `GOOGLE_APPLICATION_CREDENTIALS_BASE64`
instead (the whole service-account JSON, base64-encoded, as a plain string variable) and letting
`backend/docker-entrypoint.sh` decode it to a real file and export `GOOGLE_APPLICATION_CREDENTIALS`
before the JVM starts, since Railway's Variables tab itself only stores strings, not files.
Download the service-account key from Firebase Console → Project Settings →
Service Accounts → "Generate new private key"; never commit it to source control.

`TWO_FACTOR_API_KEY` is a plain string value (unlike the credentials file above), so it's a normal
Railway "Variable" entry — Variables tab → New Variable → paste the key from your 2Factor
dashboard. Unlike every other secret in this table, leaving it unset does NOT block startup or
degrade security; it just means `TwoFactorSmsProvider` falls back to logging transaction alerts
instead of sending them, and `ProductionConfigValidator` prints one startup warning line about it.

`CORS_ORIGINS` must list **both** frontend origins, comma-separated, no spaces around the comma
(or trim them — `CorsConfig` already trims each entry) — a mismatch here is exactly what produces
a "blocked by CORS policy" browser error on whichever app isn't listed.

Railway sets `PORT` itself — don't set it manually. The four Railway Postgres `DB_*` values are
available directly from the Postgres service's own "Connect" tab once you've provisioned it and
linked it to the backend service.

If `FINORA_SETUP_KEY` isn't set, the app starts fine — first-run bootstrap just falls back to
writing/logging a generated key instead (see `docs/bootstrap-setup-future-work.md`).
**`GOOGLE_APPLICATION_CREDENTIALS` is different: it's a hard boot-time requirement, same as
`RESEND_API_KEY`** — see the next paragraph.

### Malware scanning (ClamAV service on Railway)

Audit F-18 (2026-09-24). The backend scans every upload before any parser touches it, but only
when a scanner is configured; the code ships with `MALWARE_SCAN_PROVIDER=none`, under which
uploads pass unscanned and the prod profile prints one warning line at boot. To turn it on:

1. In the Railway project, add a new service from the Docker image `clamav/clamav:stable`.
   Give it a volume mounted at `/var/lib/clamav` (the signature database is a few hundred MB
   and is refreshed by the image's own `freshclam`; without a volume it is re-downloaded on
   every deploy). It needs no public domain: enable **private networking** only. First start
   takes a minute or two while the database downloads; the service is ready once its logs show
   `clamd started`.
2. On the backend service, set `MALWARE_SCAN_PROVIDER=clamav`, `CLAMAV_HOST=<the ClamAV
   service's private hostname, e.g. clamav.railway.internal>` and `CLAMAV_PORT=3310`.
3. Redeploy the backend and confirm the boot log line `Upload malware scanning enabled: ClamAV
   (clamd INSTREAM at ...)` replaces the `MALWARE_SCAN_PROVIDER is unset` warning.
4. Prove it end to end once: upload the EICAR test file (the 68-byte standard string, available
   from eicar.org) as a CSV statement. Expect a 400 "rejected by the malware scanner" and an
   `UPLOAD_MALWARE_REJECTED` row in the admin audit log.

While the scanner is down, uploads answer 503 "Uploads are paused while the malware scanner is
unreachable" and the backend logs at error level -- that is the default `reject` policy. Set
`MALWARE_SCAN_ON_UNAVAILABLE=allow` only for a deliberate decision to keep imports running
unscanned through a scanner incident; put it back afterwards.

Locally, `docker compose --profile scan up` starts the same image beside Postgres and Redis;
point a locally run backend at it with `MALWARE_SCAN_PROVIDER=clamav CLAMAV_HOST=localhost`.

### Generating `JWT_SECRET` — use hex

```bash
openssl rand -hex 32
```

Hex rather than base64, and the reason is measurable rather than stylistic. `ProductionConfigValidator`
rejects any secret containing a placeholder marker (`change-me`, `sample`, `dummy`, `insecure`, …),
matched case-insensitively as a substring. A randomly generated secret can contain one by chance:
over 2,000,000 generated 48-byte values, **1 base64url secret was rejected** (it happened to contain
`dumMyy`), a rate of roughly 1 in 2 million.

In **hex it is structurally impossible** — not merely unlikely. Every marker contains at least one
character outside `[0-9a-f]`, so no hex secret can ever match one. Verified by the same run: 0
rejections out of 2,000,000.

If a generated secret is ever rejected at boot, that is this collision and not a bug. Generate
another one; with hex you will not see it.

`ProductionConfigValidator` refuses to start the app at all (loud failure at boot, not a silent
insecure default, and not a `restartPolicyMaxRetries` crash-loop you have to dig through logs to
diagnose) if, while `SPRING_PROFILES_ACTIVE=prod`:
- `JWT_SECRET` or `DB_PASSWORD` are still their local-dev placeholder values,
- `RESEND_API_KEY` is blank, or
- `PhoneVerificationProvider.isConfigured()` is false — i.e. `GOOGLE_APPLICATION_CREDENTIALS`
  isn't set to a valid, readable Firebase service-account key file.

**Set all four categories above *before* the first deploy with `SPRING_PROFILES_ACTIVE=prod`.**
A real incident already happened here (back when this was `RESEND_API_KEY`/SMS-provider
credentials, before the Firebase migration): a deploy went out with `prod` active but required
config unset, and Railway crash-looped the service (`restartPolicyMaxRetries: 5` in
`railway.json` then leaves it "Crashed") — burning through the deploy's log history and free-tier
build minutes before the cause was found. Two ways out if this happens to you:
1. **Fix it properly** — set the real `RESEND_API_KEY` and a valid `GOOGLE_APPLICATION_CREDENTIALS`
   (see above) and redeploy.
2. **Unblock immediately, fix later** — remove or change `SPRING_PROFILES_ACTIVE` so it isn't
   `prod` (e.g. delete the variable, or set it to `dev`), then redeploy. This is a deliberate,
   temporary trade: `ProductionConfigValidator` only runs in the `prod` profile, so this reopens
   the exact holes it exists to catch (password-reset links leaking in API responses instead of
   being emailed; phone verification failing closed with a 503) — acceptable while you're the only
   person using the deployment to test, not once real users' accounts are on it.

## Before running more than one backend instance

**Today this app is deployed as a single Railway instance.** Raising the replica count is a
one-click change in Railway; nothing in the application will complain and no test will fail. This
section says what actually changes, from the code as it is on 2026-09-24. An earlier version of
this section described in-process limiters and "no `@Scheduled` jobs anywhere"; both statements
had been false for weeks by the time it was re-read, which is why every row below names the class
it was checked against.

### What is replica-safe already

| Control | Where | Why N instances are fine |
|---|---|---|
| Per-IP and shared rate limits (`RateLimiter`, used by `RateLimitFilter`) | Redis, one Lua script per decision (`ratelimit:<name>:<key>`) | Every instance reads and writes the same counters. Limits stay exactly as configured at any N. If Redis is unreachable, each instance counts in process with the same window and limit, so the effective limit is N× looser for the outage's duration rather than absent. |
| Import concurrency (`ImportConcurrencyLimiter`) | Redis permit pool (`app.import.max-concurrent`, default 6) | The ceiling is global, not per instance. |
| Account lockout | `users.failed_login_attempts` / `users.locked_until`, thresholds in `platform_settings` | Database-backed. |
| Refresh-token rotation and reuse detection | `refresh_tokens` | Database-backed. |
| Session revocation on every request (`SessionValidator`) | `refresh_tokens` | Database-backed. |
| Import jobs (`ImportJobWorker`), notification dispatch (`NotificationDispatcher`), merchant learning (`MerchantLearningEventWorker`) | `FOR UPDATE SKIP LOCKED` claim in each worker's repository | Two instances polling the same table claim disjoint rows. |
| Webhook processing (`WebhookEventService`) | `webhook_events` insert-if-absent | Provider redeliveries and a second instance both dedupe on the event id. |

### What double-runs, and whether that matters

There are 27 `@Scheduled` methods in the backend (`grep -rn '@Scheduled' src/main/java`). The
ones without a claim step above will run once per instance per interval. Checked individually:

| Sweep | Effect of double-running | Verdict |
|---|---|---|
| `NetWorthSnapshotSweepService`, `HealthScoreSnapshotSweepService` | Both upsert against a `UNIQUE (user_id, date)` constraint (`net_worth_snapshots`, `health_score_snapshot`), so the second run rewrites the same row with the same value. | Wasted work only. |
| `SubscriptionReconciliationSweepService`, `ReferralGrantSweepService` | Each row is processed in its own short transaction and re-read fresh inside it (`SubscriptionReconciliationSweepConcurrentRaceIT` covers the concurrent case). A second instance makes duplicate read-only calls to Razorpay. | Wasted work; watch Razorpay's rate limit at large N. |
| `AuditService.scheduledRedaction`, `RefreshTokenService.scheduledCleanup`, `EmailLoginOtpRetentionSweepService` | Idempotent deletes/updates on already-expired rows. | Wasted work only. |
| `GmailDiscoveryWorker` | Gated off in production (`GMAIL_SYNC_ENABLED=false`). | Re-check before enabling Gmail sync on more than one instance. |
| `AccountPurgeSweepService` | Has **no** claim step. Its own `MINIMUM_SAFETY_BUFFER` comment explains that two concurrent `purgeOne` runs on the same user mean interleaved deletes across ~20 tables and a double Razorpay cancellation. On one instance the 30-minute buffer prevents that; on two, both sweeps can pick the same `PENDING_DELETION` row in the same tick. | **Must add a claim before scaling** (a `pg_try_advisory_xact_lock(hashtext(user_id))` around `purgeOne`, or a conditional `UPDATE users SET status='PURGING' WHERE status='PENDING_DELETION'`). |
| `StatementStorageSweepService`, `CounterpartyBackfillSweepService`, `SharedCorpus*SweepService`, `AccountAggregator*SweepService` | Not individually verified for a second instance. | **Verify before scaling** — read the class and add a `pg_try_advisory_xact_lock` if it is not idempotent. |

### The precondition

Before increasing the replica count:

1. Re-check `DB_POOL_MAX_SIZE` (default 10) against Postgres's `max_connections`: the pool is
   **per instance**, so N instances open up to 10N connections, plus Prometheus and any shell.
2. Add the purge claim, and verify the sweep families marked "verify before scaling" above, or
   add an advisory lock to each `scheduledSweep()`.
3. Raise `RATE_LIMIT_AUTH_GLOBAL_MAX` in proportion: it protects one instance's CPU, and N
   instances have N times the bcrypt budget.
4. Update this table with what you found.

## Cloudflare (both frontends)

> **Decision on record — where each thing lives.** PostgreSQL stays on **Railway**, co-located with
> the backend, because the import pipeline makes many round-trips inside one transaction. Neon is
> reconsidered only if point-in-time recovery, per-branch databases, or a reason to separate
> database from application hosting becomes real — see
> [statement-storage-migration.md](../../architecture/data/statement-storage-migration.md) §7, which also records the
> HikariCP caution that would apply. Cloudflare's role today is **Pages for both frontends**;
> **R2 for uploaded statement files** is proposed but not built — statements currently live in
> PostgreSQL as `BYTEA`.

Both `frontend/` and `admin-portal/` build as static Vite apps. As actually deployed, that's
Cloudflare Pages (not Workers — update this section if that changes). Whatever your Cloudflare
build pipeline uses for env injection (a Pages project's Settings → Environment variables, or
Wrangler's `[vars]` if using Workers instead), set:

```
VITE_API_BASE_URL=https://<your Railway backend's public domain>
```

**The value can be either the bare origin or the origin with `/api/v1` already appended — both
now work correctly** (see `normalizeApiBase()` in `frontend/src/api/client.ts` /
`admin-portal/src/api/client.ts`). This is a fix, not just a clarification: the bare-origin form
is what actually got set in production once, and every API call silently lost the `/api/v1`
segment every backend route lives under — `register`/`login` (and everything else) hit
`<origin>/auth/register` instead of `<origin>/api/v1/auth/register`, a route that doesn't exist,
which the browser reported as a CORS failure rather than a 404 (the OPTIONS preflight itself
never matched a route to succeed against). `normalizeApiBase()` now produces the same correct
result either way, so this specific misconfiguration can't silently break every API call again —
but there's no reason not to just include `/api/v1` explicitly when setting this.

This is the fix for the frontend not being able to reach the backend at all — see
`frontend/src/api/client.ts`'s own doc comment for the full explanation: the relative `/api/v1`
path this used to hardcode only ever worked through Vite's *dev-server* proxy, which has no
effect at all on the built, deployed static output.

`admin-portal/` additionally has `VITE_BACKEND_ORIGIN`, used only for a couple of direct
human-facing links (Swagger/Actuator) that can't go through the API client at all — set it to the
same Railway backend URL as `VITE_API_BASE_URL` above (bare origin, no `/api/v1` — this one really
is just the origin, used to build a Swagger UI link directly).

### Which document a path gets (user frontend)

`app.fynora.net` is a Cloudflare **Pages** project, so Pages' own rules decide what a URL returns,
not `frontend/wrangler.jsonc`. That file's `assets.not_found_handling` is Workers configuration:
it shapes `wrangler dev` (`npm run preview`) and nothing in production, because Pages only reads a
Wrangler file that has `pages_build_output_dir`. Test routing changes with
`npx wrangler pages dev dist` and on a PR's Pages preview. `wrangler dev` will agree with
production on some of this and disagree on the rest, with no warning.

| Path | Served | How |
|---|---|---|
| `/` and the pages listed in `scripts/prerender.mjs` (`/privacy`, `/terms`, ...) | that page's prerendered HTML | a file in `dist/` (`index.html`, `privacy.html`, ...) |
| `/auth`, `/login`, `/register`, `/forgot-password`, `/reset-password`, `/verify-email`, `/verify-phone`, `/email-change-verify`, `/app`, `/app/...` | the blank shell, `dist/spa-shell.html` | a `200` rewrite in `public/_redirects` |
| any other path | the not-found page, `dist/404.html`, with a `404` | Pages' answer for an unmatched path when the build has a top-level `404.html` |
| a missing file under `/assets/` | `404`, plain text | `functions/assets/[[path]].ts`, which replaces the not-found page Pages would send |

The blank shell is the built document with an empty `#root`. It exists because neither document
Pages would otherwise send for those routes is theirs: `index.html` is the prerendered homepage
(until `_redirects` named them, the routes in the second row painted the homepage's headline and
buttons until the bundle ran, about 1.6 s on a throttled phone, cold), and `404.html` is the
not-found page with a `404` status.

`404.html` is the not-found page (`src/pages/NotFound.tsx`), prerendered by `scripts/prerender.mjs`.
It is deliberate. Without it an unknown URL returned the homepage with a `200`, which the
2026-10-09 SEO audit flagged as a soft 404.

It is also what lets `dist/index.html` carry the homepage's canonical and `og:url`. With it,
`index.html` is served at `/` and nowhere else: `/index` and `/index.html` answer `308` to `/`, and
a path without a file gets `404.html` (measured on a Pages preview and on production, 2026-10-10).
Remove `404.html` and that canonical would again be served at every unknown URL. The blank shell and
`404.html` itself carry no canonical and no `og:url`; `scripts/seoFiles.test.tsx` holds all three.

Things that are easy to get wrong here. Each was read from Cloudflare's parser and asset handler
(wrangler 4.146.0) and measured on a Pages preview, except the trailing-slash one, which was
measured in the local Pages emulator only:

- **The top-level `404.html` is why `_redirects` is load-bearing.** Pages has no fallback to
  `index.html` while that file exists: every path without a file or a rewrite returns `404` with
  the not-found page. The rewrites are the only thing that keeps `/auth`, an emailed
  `/reset-password` link and everything under `/app` at `200`. Measured both ways on Pages previews:
  with `404.html` and no rewrites, `/auth`, `/app/transactions` and `/reset-password` all returned
  `404` (and once before, see `docs/investigations/incidents/2026-08-08-stale-chunk-login-failure.md`
  §4a); with both, the rewritten routes return `200` and only unknown paths `404`. Never delete
  `_redirects`, or a line from it, as tidying.
- **A browser can hide a missing rule.** The `404` response is still a document that loads the
  bundle, so React mounts the real page over the not-found page a moment later. A route that is
  missing from `_redirects` therefore looks fine to someone clicking around, and is broken for
  everything that reads the status or does not run the bundle (link checkers, crawlers, an
  uptime probe, the first paint). Check with `curl`, not by eye.
- **Matching is case-sensitive.** `/Privacy` and `/Auth` return `404` with the not-found page, and
  the browser then shows the real page, as above. Before `404.html` they returned `index.html`
  with a `200`. The paths the backend's emails and the referral page build are lower-case.
- **A rewrite is applied before the file lookup.** A rule that matched `/` or a prerendered page
  would replace crawlable HTML with the blank, `noindex` shell.
- **Rewrite to `/spa-shell`, not `/spa-shell.html`.** Pages answers an `.html` destination with a
  `308` to the extensionless URL. `/* /index.html 200` fails differently: the parser drops it as an
  infinite loop.
- **An exact rule does not match its trailing-slash form**, so each one is listed twice.
- **A new route outside `/app` needs its own line, in both forms** (`/x` and `/x/`). If it is
  forgotten, a direct visit to the route returns `404`. `scripts/spaShell.test.ts` fails when a
  route in `App.tsx` is neither prerendered nor rewritten, and it is the only guard: it reads the
  routes from the source, so a route behind a build flag is covered, but a route declared any
  other way than `<Route path="/literal">` in `App.tsx` is not (the test fails on that shape too,
  so that it gets taught rather than skipped).
- **An unknown address under `/app` is a `200`, not a `404`.** The `/app/*` rule sends it to the
  blank shell, and React then shows the not-found page. Accepted: `robots.txt` disallows `/app`
  and the shell is `noindex`.
- **`wrangler dev` (`npm run preview`) still answers every unknown path with `index.html` and a
  `200`.** It shows neither the `404` nor a missing rewrite.

Verify after a deploy that touches any of this. Expect `200` three times and `404` twice; then an
empty `#root` for `/auth` and `/app/transactions`, the homepage's `<h1>` for `/`, the not-found
page for the unknown path, and plain text for the missing asset:

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://app.fynora.net/
curl -s -o /dev/null -w "%{http_code}\n" https://app.fynora.net/auth
curl -s -o /dev/null -w "%{http_code}\n" https://app.fynora.net/app/transactions
curl -s -o /dev/null -w "%{http_code}\n" https://app.fynora.net/no-such-page
curl -s -o /dev/null -w "%{http_code} %{content_type}\n" https://app.fynora.net/assets/no-such-file.js # 404 text/plain
curl -s https://app.fynora.net/auth | grep -c '<div id="root"></div>'             # 1
curl -s https://app.fynora.net/app/transactions | grep -c '<div id="root"></div>' # 1
curl -s https://app.fynora.net/ | grep -c '<h1'                                    # 1 or more
curl -s https://app.fynora.net/no-such-page | grep -c 'Page not found'             # 1 or more
```

If `/auth` or `/app/transactions` is ever `404`, the app is down for direct visits and emailed
links: roll the Pages deployment back first, then look at `_redirects`.

### `VITE_SENTRY_DSN` (both frontends) — crash reporting

Optional, and unset is a valid, fully-working configuration: `src/lib/monitoring.ts` no-ops
without it. But leaving it unset means a crash in either web app is invisible to you — which is
the exact situation these apps were already in, while the mobile app had crash reporting. A blank
white page from a render error is the failure this catches, and there is no other mechanism that
would tell you it happened.

```
VITE_SENTRY_DSN=https://<key>@<org>.ingest.sentry.io/<project>
```

**It must be set as a *build* environment variable, not a runtime one.** Vite inlines
`import.meta.env.*` at build time, so a value added to the Pages project after a deployment has
already been built has no effect until the next build. This is the same class of mistake as the
`VITE_API_BASE_URL` bug above: everything looks configured and nothing reports.

The upside of that inlining is worth knowing, and was measured rather than assumed: with the DSN
unset the entire Sentry SDK is tree-shaken out of the bundle (verified — zero occurrences of
`sentry` in `dist/`, user app at 820 kB). With it set the same build is 911 kB, about **+30 kB
gzipped**. So crash reporting costs nothing at all until you actually turn it on, and the cost when
you do is a known number.

What leaves the browser is deliberately much narrower than Sentry's defaults, because this app's
URLs carry the ledger search term (`?q=`), password-reset tokens (`?token=`), and — in the admin
portal — the ids of the customers an admin was viewing. See `src/lib/monitoring.ts` in either app
for exactly what is stripped and why; the scrubbers are unit-tested, because scrubbing that
silently stops working looks identical to scrubbing that works.

### Sentry release tagging and source maps (both frontends)

Optional, and everything above works fully without it -- a build without these still deploys and
reports crashes exactly as described above, just with minified stack traces instead of real file
and line numbers.

**Release tagging needs nothing set here.** `vite.config.ts` in each app reads
`CF_PAGES_COMMIT_SHA`, which Cloudflare Pages injects into the build environment automatically
for every build (Production and Preview alike) -- no dashboard configuration needed. It's wired
through as the Sentry release name (`__APP_RELEASE__` in `vite-env.d.ts`, consumed by
`lib/monitoring.ts`'s `Sentry.init`) so an error groups by deploy rather than by "production" as a
whole. The backend does the equivalent from Railway's own auto-injected `RAILWAY_GIT_COMMIT_SHA`
-- see `sentry.release` in `application.yml`.

**Source map upload needs three build environment variables**, set in Cloudflare Pages' project
Settings -> Environment variables (recommended: Production bucket only -- every open PR's Preview
build already shares Firebase Dev-tier config per the Dev environment section below, but there's
no reason for every preview build to also upload a Sentry release):

```
SENTRY_ORG=<your org slug, from your Sentry URL>
SENTRY_PROJECT=<your project slug, from your Sentry URL>
SENTRY_AUTH_TOKEN=<a Sentry auth token with project:releases scope -- a real secret, never commit it>
```

`vite.config.ts` applies the `@sentry/vite-plugin` only when all three are present, the same
"absent config degrades to no-op" posture as `VITE_SENTRY_DSN` above -- a build missing any of
them still succeeds, it just doesn't upload maps. The uploaded `.js.map` files are deleted from
the built output immediately after upload (`sourcemaps.filesToDeleteAfterUpload` in each
`vite.config.ts`), so they never end up served publicly from `dist/` -- Sentry has its own copy by
the time this deletes them, and the app's own bundle already only ships hidden-sourcemap
references (`build.sourcemap: 'hidden'`), not maps anyone's browser would fetch.

**Also verify `CORS_ORIGINS` on the Railway backend matches your ACTUAL deployed frontend
origin(s) exactly** — scheme, host, no trailing slash. Cloudflare Pages assigns its own
`<project-name>.pages.dev` domain (and a different one per preview deployment) by default; once a
custom domain is attached (Pages project → Custom domains — e.g. `app.fynora.net` /
`admin.fynora.net`, both proxied through the same Cloudflare account the apex domain's DNS
lives in), that becomes the real production origin and `CORS_ORIGINS`/`APP_BASE_URL`/
`ADMIN_APP_BASE_URL` on the backend must be updated to match it — the `.pages.dev` origin keeps
working alongside a custom domain (Cloudflare doesn't disable it), so nothing breaks immediately if
you forget, but it means the "production" URL and the URL the backend actually trusts have quietly
diverged. A mismatch here produces the same "blocked by CORS policy" browser error as the
`/api/v1` bug above, so if requests still fail after fixing `VITE_API_BASE_URL`, this is the next
thing to check. Attaching a custom domain to an existing Pages project does **not** require a new
build — unlike a `VITE_*` variable change, this one takes effect without a redeploy.

**Two things a domain migration is easy to forget, neither of which fails loudly:**
- **Resend domain verification.** `EMAIL_FROM`/`RESEND_API_KEY` being set is not the same as Resend
  being *willing* to send as that address — the sending domain must be added and verified in
  Resend's dashboard (Domains → Add Domain), which means adding the SPF/DKIM records Resend
  provides to the domain's DNS (Cloudflare, in our case). Skip this and sends either fail silently
  or land in spam; `ProductionConfigValidator` has no way to check it, since "is this domain
  verified" is a fact that lives entirely on Resend's side.
- **Firebase Authorized Domains.** Phone verification (registration, password reset, authenticated
  password change — see `FirebaseConfig`) runs client-side via the Firebase Web SDK, which refuses
  to complete `signInWithPhoneNumber` from any origin not on Firebase Console → Authentication →
  Settings → **Authorized domains**. The `.pages.dev` domains are on that list today; the new custom
  domains are a **different origin** and won't be, until added there manually. Nothing else in this
  guide's checklist (CORS, `APP_BASE_URL`, Resend) touches this list — it's tracked only by Firebase,
  so it's the one step a domain cutover silently breaks if skipped: every OTP screen on the new
  domain fails with `auth/unauthorized-domain` while the rest of the app works normally.

**`finoratech.info` is a hard cutover, not a graceful migration (2026-08-25).** This section
used to describe setting up 301 redirects from `finoratech.info`/`app.finoratech.info`/
`admin.finoratech.info`/`api.finoratech.info` to their `fynora.net` equivalents. That plan is
dead: `finoratech.info` was sold to a third party — Railway's edge is healthy and reachable, but
the domain's own nameserver delegation now points at the buyer's registrar (Afternic parking
nameservers, confirmed via `dig +trace`), not at Cloudflare, no matter what records exist inside
Cloudflare's dashboard for it. Nobody on this project can add a redirect, a DNS record, or
anything else to a domain they no longer control. Treat every `finoratech.info` link, email, and
API allowance as **untrusted**, not as a domain to migrate away from politely:

- **CSP no longer allows `api.finoratech.info`.** `frontend/public/_headers` and
  `admin-portal/public/_headers` used to keep it listed in `connect-src` "during the transition" —
  removed. A CSP entry for a domain someone else now owns is an exfiltration path, not a
  compatibility nicety, and there is no transition to keep it for.
- **No redirects, ever, for this domain.** Any `finoratech.info` link already out in the world
  (old emails, old bookmarks, search results) is simply broken now. That's the cost of the
  domain changing hands, not something a config change here can fix.
- **Google Cloud OAuth, Railway's custom domain, and Cloudflare Pages' custom domains** for
  `finoratech.info` and its subdomains (`app.`, `admin.`, `api.`, and the `dev-*` tier — see "Dev
  environment" below, which has its own live `finoratech.info` references still pointing at
  infrastructure that needs to stop trusting that domain) all need removing directly in their
  respective consoles — none of that is expressible in this repo.
- **Search Console.** Add `fynora.net` as a property (Cloudflare's existing DNS makes domain-level
  verification via a TXT record the fastest path), then use URL Inspection → Request Indexing on
  the handful of pages that matter for organic traffic (landing, About, Careers) rather than
  waiting on the crawl queue. There is no sitemap in this repo to submit — the site is small enough
  that request-indexing the key pages directly is faster than building one. No point requesting
  deindexing of the old domain's pages — that's now the buyer's content, not this project's to
  manage either way.

## Dev environment (admin-portal, frontend, mobile)

The backend already runs on two Railway environments — Production (`api.fynora.net`) and Dev.
This section used to say no `fynora.net` Dev-tier equivalent existed yet — no longer true for two
of the three. Verified directly (`dig` + `curl /actuator/health` or a plain request), not assumed:
`dev-api.fynora.net` and `dev-app.fynora.net` are live, resolving to the same Cloudflare edge as
production and answering 200; `dev-admin.fynora.net` does not resolve yet. So `dev-admin.` is the
one still needing the same treatment `finoratech.info`'s hard-cutover note describes for
production — remove the stale `dev-admin.finoratech.info` Cloudflare Pages custom domain and
re-add `dev-admin.fynora.net` once DNS for it exists — while `dev-api.`/`dev-app.` just need every
remaining `finoratech.info` reference below (`CORS_ORIGINS`/`APP_BASE_URL`, Cloudflare Pages'
Preview env bucket, `mobile/eas.json`'s `dev` profile — see
`docs/engineering/mobile/mobile-setup.md`) updated to the `fynora.net` value that already works.
This section covers giving the three
client surfaces (admin-portal, frontend, mobile) a matching Dev tier, so a feature can be
exercised end-to-end against a live backend before it ever touches production data, Firebase, or
real Google accounts.

**Nothing shared with Production here — a deliberately separate Firebase project.** Production's
convention (one Firebase project, same values in both `frontend/` and `admin-portal/` — see
"Frontend environment variables" below) still holds *within* each tier, but Dev gets its own
project, its own service-account key, and its own Google Sign-In OAuth client, not Production's.
Testing against Dev should never send a real SMS through Production's Firebase project or
authenticate against a real Google account tied to Production's OAuth consent screen.

**`dev` is a persistent git branch**, not a feature branch, protected by its own ruleset with the
same rules as `main`'s (see "Branch protection" below). `.github/workflows/sync-dev-branch.yml`
keeps it caught up with `main`'s tip on every push to `main`. A direct push would be rejected by the
ruleset, so the sync goes through a pull request:
1. It opens, or reuses, a `main → dev` PR.
2. It waits for `ci.yml`'s push run on that exact `main` commit to finish with `success`.
3. It merges the PR with a merge commit (`gh pr merge --merge --match-head-commit`).

It does not use auto-merge; that workflow's own header explains why. The sync PR still has to
satisfy `dev`'s required checks, like any other change to a protected branch. While the repository
is private, the sync runs only when started by hand; see
[`ci-visibility-profiles.md`](../../architecture/infrastructure/ci-visibility-profiles.md). Cloudflare
Pages binds `dev-app.fynora.net` (live, verified) / `dev-admin.finoratech.info` (not yet migrated —
see the "Dev environment" section above) to this branch as a
**branch-alias custom domain** (Pages project → Settings → Custom domains → set up a custom
domain, then repoint that hostname's DNS CNAME at `dev.<pages-project>.pages.dev` instead of the
bare `<pages-project>.pages.dev`) — not a second Pages project.

### Branch protection (`main` and `dev`)

Each branch has its own repository ruleset: "main branch protection" (targets the default branch)
and "dev branch protection" (targets `refs/heads/dev`). Their rules are identical. Read from the
GitHub API on 2026-10-04; re-check with `gh api repos/Fynora/finora/rulesets` before relying on
this:

- **A pull request is required**, so there are no direct pushes. Merge, squash and rebase merges
  are all allowed.
- **Deliberately no required approving reviews** (`required_approving_review_count: 0`). This repo
  has no second human reviewer today, so requiring one would block merging your own PRs entirely.
  Revisit this once that changes.
- **Required status checks: `Detect changed areas`, `Repository hygiene (all clients)` and
  `Secret scan (gitleaks)`.** These three are the `ci.yml` jobs that run on every PR (in the public
  profile) regardless of which files changed. The heavier jobs (backend, frontend, admin portal, mobile, smoke) are
  path-filtered, so they are not required checks. A required check that was skipped would be
  reported as passing anyway.
- **Not strict** (`strict_required_status_checks_policy: false`): a PR's branch does not have to
  be up to date with the base before merging. `.github/workflows/migration-order.yml` re-checks
  open PRs' Flyway versions whenever `main`'s migrations change, because nothing else would.
- **Branch deletion and force-pushes are blocked.**
- **No bypass actors**, so the rules apply to admins too.

These rulesets are enforced only while the repository is **public**. On GitHub Free, rulesets
apply to private repositories only on paid plans. See
[`ci-visibility-profiles.md`](../../architecture/infrastructure/ci-visibility-profiles.md).

The repo's "Allow auto-merge" setting is on. Today only `github-traffic-metrics.yml` uses it
(`gh pr merge --auto`), and auto-merge is likewise public-only on GitHub Free.

Cloudflare Pages' environment-variable UI has only two buckets, Production and Preview — there is
no native per-branch scoping. The Dev-specific `VITE_*` values (the six `VITE_FIREBASE_*` keys,
`VITE_API_BASE_URL=https://dev-api.fynora.net`, plus `VITE_GOOGLE_LOGIN_CLIENT_ID` on
`frontend/` and `VITE_BACKEND_ORIGIN` on `admin-portal/`) go in the **Preview** bucket — which
means every open PR's preview deployment also picks them up, not just the `dev` branch. That's the
intended outcome: no PR preview should ever be able to reach Production's Firebase project or data.

**Railway's Dev environment** needs its own `CORS_ORIGINS`/`APP_BASE_URL`/`ADMIN_APP_BASE_URL`
(pointed at the two `dev-*` origins, same format as the Production values documented above) plus its
own `GOOGLE_APPLICATION_CREDENTIALS_BASE64` and `GOOGLE_LOGIN_CLIENT_IDS` (the Dev Firebase
project's own service-account key and OAuth client id — see the Railway section above for exactly
how each of those is shaped; the Dev environment's copies just point at the new project instead of
the existing one).

**Mobile has no cloud-built Dev profile.** `mobile/eas.json`'s `dev` build profile inlines
`EXPO_PUBLIC_API_BASE_URL=https://dev-api.fynora.net` directly (no confidentiality reason to
route a public API origin through EAS's environment-variable store — see `mobile-setup.md` for why
`EXPO_PUBLIC_*` values are inlined into the client bundle regardless), but a genuinely custom EAS
environment name for the Dev Firebase config files is only available on a paid EAS plan. Build the
`dev` profile locally instead (`eas build --profile dev --platform android --local`, and the iOS
equivalent), with the Dev project's `google-services.json`/`GoogleService-Info.plist` physically
present in `mobile/` at build time — same file-based convention the existing `development` profile
already uses. See `docs/engineering/mobile/mobile-setup.md` for the full walkthrough.

## Search engines: which host is indexed

**`app.fynora.net` is the one indexed host** (owner decision, 2026-09-24; the constant is
`SITE_ORIGIN` in `frontend/src/lib/siteUrl.ts`). Checked against production that day: `fynora.net`
answers with a 301 to `https://app.fynora.net/`, and `www.fynora.net` and `app.fynora.net` both
serve the site directly. `fynora.net` cannot be canonical while it redirects to `app.`; making it
canonical is an infrastructure change (serve the apex, redirect `app.*` to it, keep the app-link
files and CORS working) that has not been made.

What the build produces, all from `frontend/`:

- `public/robots.txt` and `public/sitemap.xml`: keep crawlers out of `/app` and every auth flow, and
  list the 12 public routes. `scripts/seoFiles.test.tsx` fails if a route in `App.tsx` is neither
  in the sitemap nor disallowed.
- Every prerendered public page, the homepage included, has its own `<title>`, description, `og:`
  tags and an absolute canonical in its built HTML. The homepage's canonical and `og:url` are added
  to `dist/index.html` by the build (`templateForHomepage` in `scripts/prerenderTitle.mjs`), not
  written in the source `frontend/index.html`: that file is the template for every other document,
  and the blank shell and the not-found page must name no address. This is safe only because
  production serves `index.html` at `/` alone (see "Which document a path gets" above). While it was
  also the answer for every path without a file, a canonical in it named the homepage as the
  address of those pages.
- **Non-production builds are `noindex`** (`scripts/crawlPolicy.mjs`, the last step of `npm run
  build`): an `X-Robots-Tag` header, a robots meta tag, no canonical, and no Sitemap line. A build is
  non-production if Cloudflare reports a branch other than `main` (`CF_PAGES=1`, `CF_PAGES_BRANCH`),
  or if it uses the dev API (`VITE_API_BASE_URL=https://dev-api.fynora.net`, which the Preview
  bucket sets). A build it cannot identify is treated as production, so a missing variable can never
  de-index the site. It does not use `Disallow: /`: a crawler that may not fetch a page never sees
  its noindex.
- **`X-Robots-Tag` is inserted into the existing `/*` block of `_headers`, never added as a second
  `/*` block.** Measured on a real preview: with two `/*` blocks Cloudflare stopped sending the
  first block's headers (no `Content-Security-Policy`, no `Strict-Transport-Security`) while sending
  the second's. Cloudflare's docs read as if matching blocks merge; deployed, they did not. The same
  applies to anything else that edits `_headers`.

**Both non-canonical hosts redirect to `app.fynora.net` at Cloudflare's edge, not in this repo.**
They are two **Page Rules** on the `fynora.net` zone (Rules → Page Rules, 2 of 3 free-plan rules
used), both "Forwarding URL", 301, destination `https://app.fynora.net/$1`:

| URL pattern | Added |
|---|---|
| `fynora.net/*` | earlier (the original apex redirect) |
| `www.fynora.net/*` | 2026-09-24 (before this, `www` served a full duplicate of the site with a 200) |

It is done at the edge on purpose. Doing it in this repo would need a Pages Function on every
request, and this project keeps its only Function scoped to `/assets/` for exactly that cost reason.
Page Rules matching a host name only ever affect that host: `app.fynora.net` and `dev-app.fynora.net`
are not matched. Path and query string are carried over (`$1`).

Verify after any change to those rules, expecting the first three to be 301 and the last two 200:

```bash
curl -sI https://www.fynora.net/            # 301, location: https://app.fynora.net/
curl -sI "https://www.fynora.net/terms?a=1" # 301, location: https://app.fynora.net/terms?a=1
curl -sI https://fynora.net/terms           # 301, location: https://app.fynora.net/terms
curl -sI https://app.fynora.net/            # 200
curl -sI https://dev-app.fynora.net/        # 200
```

After each deploy that changes the SEO files, check `https://app.fynora.net/robots.txt` returns plain
text (not the app's HTML). The sitemap `https://app.fynora.net/sitemap.xml` was submitted in Search
Console on 2026-09-24, under the `fynora.net` **Domain** property (which covers `app.` and every other
subdomain; a URL-prefix property is not needed and a path-level one, made by pasting the sitemap URL
into "Add property", is useless).

## Frontend environment variables

| App | Variable | Required in prod? | Purpose |
|---|---|---|---|
| `frontend/` | `VITE_API_BASE_URL` | **Yes** | Backend's absolute origin for every API call |
| `frontend/` | `VITE_LOGODEV_TOKEN` | No | Optional bank- and merchant-logo lookups (BankLogo.tsx, MerchantLogo.tsx) via Logo.dev; unset just skips that step. Free-tier commercial use needs attribution -- see `frontend/.env.example` |
| `mobile/` | `EXPO_PUBLIC_LOGODEV_TOKEN` | No | Merchant-logo lookups (MerchantLogo.tsx) via Logo.dev; unset just skips that step. Same free-tier attribution requirement as frontend's VITE_LOGODEV_TOKEN -- still unresolved on both platforms. |
| `frontend/` | `VITE_GOOGLE_LOGIN_CLIENT_ID` | No | D-23 "Sign in with Google" web OAuth client id (`GoogleSignInButton.tsx`) — must match one of the backend's `GOOGLE_LOGIN_CLIENT_IDS` above. Unset hides the Google button entirely rather than rendering one that can't work. |
| `frontend/` | `VITE_GMAIL_SYNC_UI_ENABLED` | No | Gmail sync is paused (`docs/engineering/gmail-sync-paused.md`). **Leave unset**: the web app then hides every way into it. Only the exact value `true` shows it again. Build-time, so a change needs a redeploy; switch the backend's `GMAIL_SYNC_ENABLED` on first. |
| `frontend/` | `VITE_PREMIUM_PLAN_VISIBLE` | No | Premium plan visibility (`src/lib/premiumVisibility.ts`). **Leave unset**: the web app then hides Premium everywhere. Only the exact value `true` shows it again. Build-time, so a change needs a redeploy. Mobile's own flag (`EXPO_PUBLIC_PREMIUM_PLAN_VISIBLE`) is flipped separately, with a new build/OTA update. |
| `frontend/` | `VITE_OPEN_IN_APP_ANDROID` | No | "Open in the Fynora app" bar on Android phone browsers (`src/lib/openInApp.ts`); its button opens the installed app, or the Play Store listing when it isn't installed. **Leave unset until the Android app is live on Google Play.** Only the exact value `true` shows it. Build-time, so a change needs a redeploy. |
| `frontend/` | `VITE_IOS_APP_STORE_ID` | No | The iOS app's App Store id (the digits in its App Store URL). When set, `index.html` gets Apple's Smart App Banner, which Safari shows as "Open" or "Get". **Leave unset until the iOS app is live on the App Store.** Build-time, so a change needs a redeploy. |
| `admin-portal/` | `VITE_API_BASE_URL` | **Yes** | Same as above, this app's own API client |
| `admin-portal/` | `VITE_BACKEND_ORIGIN` | Yes, if Diagnostics' Swagger/Actuator links are used | Direct human-facing links that can't go through the API client |
| both | `VITE_FIREBASE_API_KEY` / `VITE_FIREBASE_AUTH_DOMAIN` / `VITE_FIREBASE_PROJECT_ID` / `VITE_FIREBASE_STORAGE_BUCKET` / `VITE_FIREBASE_MESSAGING_SENDER_ID` / `VITE_FIREBASE_APP_ID` | **Yes** | Firebase Web SDK config (`lib/firebase.ts` in each app) that powers Firebase Phone Authentication (OTP send/confirm for registration, password reset, and admin password change) | **No.** Without these, `getFirebaseAuth()` throws the moment a phone-verification screen is actually used (`VerifyPhone.tsx`/`ResetPassword.tsx`) — everything else in the app keeps working since Firebase init is lazy, not at module load. |

Copy Firebase Console → Project Settings → General → "Your apps" → the SDK config snippet's six
values directly into the six `VITE_FIREBASE_*` vars above — this is the same Firebase project the
backend's `GOOGLE_APPLICATION_CREDENTIALS` service account belongs to, and the same values in both
`frontend/` and `admin-portal/`. Not secrets in the way an API key to a paid/quota-limited service
would be (Firebase's own docs treat this config as safe to ship in a client bundle; access control
is enforced server-side via the Admin SDK, not by hiding this object) — but still real per-project
values, not placeholders.

`.env.example` in each app's root documents these with the same detail as the table above — copy
to `.env.local` for local overrides, or configure via your deploy pipeline's own env injection for
the actual Cloudflare deployment.
