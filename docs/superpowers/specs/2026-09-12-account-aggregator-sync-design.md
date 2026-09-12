# Account Aggregator sync — design

Status: proposed, not yet implemented. Premium-gated, post-launch scope (same bucket as Gmail Sync).

## Problem

Bank statement import today is entirely manual (PDF/CSV upload, OCR/parse, user confirms). No mechanism auto-imports a bank statement every month. This spec adds one, via India's RBI-regulated Account Aggregator (AA) framework, for Premium users.

## Regulatory approach

Fynora does not become a licensed FIU (Financial Information User) directly — that needs its own RBI/Sahamati registration, months of compliance work, wrong call pre-launch. Instead, integrate with **Setu**, a TSP (Technical Service Provider) already registered as an FIU-for-hire: Fynora calls Setu's REST API, Setu holds the regulatory relationship, Fynora pays per data-pull.

**Roles:** FIP (bank, holds the data) → AA (consent broker the user already has, e.g. Finvu/OneMoney app) → FIU (Setu, on Fynora's behalf).

## Scope

- Gated behind a new `FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC`, same pattern as `GMAIL_SYNC`. Free/Plus users stay manual-import-only.
- FI types: `DEPOSIT` (savings/current) and `CREDIT_CARD`. Coverage for `CREDIT_CARD` is issuer-by-issuer and not verified yet — needs checking against Setu's live FIP list before build (explicit open item, not guessed).
- Historical backfill on first link: last 3 months.
- Fetch cadence: recurring (daily), not one-time or manual-trigger-only — this is what "automatically imported every month" actually requires.
- Runs **alongside** manual import, but per-account, not per-user: an account only has one active source at a time (see Data model below). Accounts not on the AA network, or not linked, stay on manual import as today.

## Architecture

New package `integrations/setu/` (mirrors `integrations/google/`):

- `AccountAggregatorLink` — entity, one row per linked FI account. Fields: id, userId, linked `Account` id, consent handle id, FI type (`DEPOSIT`/`CREDIT_CARD`), status (`CONSENT_PENDING`/`ACTIVE`/`PAUSED`/`REVOKED`/`EXPIRED`), consent expiry, `lastSyncedAt`, `lastSyncStatus`.
- `SetuConsentService` — creates a consent request, returns the AA-app redirect URL.
- `AccountAggregatorWebhookController` — receives Setu webhooks (`consent.approved`, `consent.revoked`, `data.ready`), signature-verified (same shape as `RazorpayWebhookDispatcher`).
- `SetuDataFetchService` — on `data.ready`, calls Setu's fetch endpoint, decrypts the FI data payload (AA spec's ECDH key exchange).
- `AccountAggregatorTransactionMapper` — maps decrypted `DEPOSIT`/`CREDIT_CARD` JSON into `Transaction` rows, `Source.ACCOUNT_AGGREGATOR`.
- `AccountAggregatorReconciliationSweepService` — scheduled safety net (mirrors `SubscriptionReconciliationSweepService`): finds links with no sync past their expected cadence + grace window, force-fetches directly rather than waiting on a webhook that may never arrive.
- `AccountAggregatorLinkController` — REST API: initiate link, list linked accounts, revoke.

**Why webhook-driven, not poll-only (like `GmailDiscoveryWorker`):** Gmail has no push option, so polling is the only choice there. AA data becomes ready on the bank's own schedule and Setu can push a notification — polling for "is it ready yet" either lags (long interval) or wastes calls (short interval). This codebase already has a proven webhook + reconciliation-sweep pattern for exactly this shape of problem (Razorpay billing), reused here rather than inventing a third pattern.

## Data model

- New `Transaction.Source.ACCOUNT_AGGREGATOR` value, alongside `MANUAL`/`CSV_IMPORT`/`GMAIL_IMPORT`.
- New `Account.primarySource`: `MANUAL` (default, current behavior) or `ACCOUNT_AGGREGATOR`.
- `SourceTrust.of()` gets an `ACCOUNT_AGGREGATOR` case. The class's own existing doc comment proposes trust 100 (above `CSV_IMPORT`'s 95, reasoning: "live bank feed vs. a document someone chose to upload"). This design sets it **below** `CSV_IMPORT` instead, until AA parsing has real production mileage — CSV/PDF parsing is validated against a real, multi-month corpus; AA's decrypt/map layer ships with none. Revisit once validated. (No `default` branch in that switch — the compiler forces this case to be added the moment the enum value exists.)

## Flow

**1. Linking.** Premium user taps "Link bank/card" → entitlement check → `SetuConsentService` creates consent, scoped to `DEPOSIT` and/or `CREDIT_CARD` → user redirected to their own AA app, approves. If the chosen card's issuer isn't an AA-participating FIP for `CREDIT_CARD`, linking that card is unavailable — same fallback as "user chose not to link," not a hard error.

**2. Consent approved.** Setu webhooks `consent.approved` → `AccountAggregatorLink` created, `ACTIVE` → matched against an existing `Account` by bank + masked account number/IFSC (same identifiers Fynora already stores from manual imports). Match found → attach, flip that `Account.primarySource` to `ACCOUNT_AGGREGATOR`. No match → create a new `Account` with that source. From this point, manual upload for *this account* is blocked — both UI (hide control) and API (reject server-side; hiding a button is not enforcement).

**3. One-time historical backfill (3 months).** Fetch → decrypt → map → insert `Transaction` rows. Then reconcile against whatever was already manually imported for that account:
   - The existing exact-match duplicate pass (`ReconciliationService`, composite key: account+date+amount+description) already catches identical-narration overlaps, with `SourceTrust` deciding which row is canonical.
   - A new fuzzy near-duplicate pass (same shape as the existing Gmail cross-source pass, simpler — no merchant-brand token reduction needed, just same account + same amount + tight date window + normalized-description similarity) catches cases where AA's narration field isn't byte-identical to what OCR/CSV parsing extracted. Not established whether the exact pass alone would have been sufficient — no real AA sample data yet to check narration format against; build both.
   - Ambiguous ties (multiple plausible matches on either side) are never auto-resolved — land both, flagged for user review.

**4. Ongoing recurring sync.** Bank pushes new data to Setu on the agreed schedule → Setu webhooks `data.ready` → fetch → decrypt → map → upsert `Transaction` idempotently on Setu's transaction id. `ReconciliationService`'s existing Gmail cross-source pass (pass 4) picks up AA-sourced rows with **no code change** — its candidate filter is `source != GMAIL_IMPORT`, an exclusion, not an allowlist of the two sources that exist today.

**5. Missed-webhook safety net.** `AccountAggregatorReconciliationSweepService` runs on a schedule, finds any `ACTIVE` link whose `lastSyncedAt` is older than expected cadence + grace period, force-fetches directly.

**6. Entitlement / consent state changes.**
   - Downgrade to Free/Plus → link `PAUSED` (not deleted, consent may still be valid), linked `Account.primarySource` reverts to `MANUAL`, manual upload unblocks again.
   - User revokes consent in their AA app (out-of-band) → webhook `consent.revoked` → link `REVOKED`, same unblock.
   - Consent expiry (AA consents expire, typically max ~1yr) → `EXPIRED`, same unblock.
   - Both the webhook handler and the sweep re-check entitlement before processing a tick, mirroring `GmailDiscoveryWorker`'s existing downgrade check — a downgraded user's link stops costing Setu-pull money immediately, without deleting state.

## Error handling

- Bad webhook signature → reject 401, log, no retry (Setu's responsibility to retry — same as `RazorpayWebhookDispatcher`).
- Fetch/decrypt failure (transient: timeout, 5xx, rate limit) → log, do not retry inside the webhook handler. The reconciliation sweep is the retry mechanism — same "the run IS the unit of retry" principle `GmailDiscoveryWorker`'s own doc comment already states for Gmail.
- Consent revoked or expired → stop scheduling for that link; no error, expected terminal state.
- Ambiguous account match → never guessed; new unlinked `Account`, flagged for user merge.

## Known gaps this design also closes (found by reading the existing code, not hypothesized)

- `TransactionExplanationService` currently special-cases only `GMAIL_IMPORT` for its explanation text; needs an `ACCOUNT_AGGREGATOR` case or it silently inherits the generic/CSV explanation, which is misleading ("from your statement upload" is not true for a live feed).
- `mobile/src/components/TransactionSourceModal.tsx`'s `SOURCE_LABELS` map is hand-written, not OpenAPI-generated (unlike `mobile/src/api/generated-types.ts`). Confirmed today: an unmapped source falls back to `"No source information is available for this transaction."` — needs an `ACCOUNT_AGGREGATOR` entry or every AA transaction shows that string. Web has no equivalent file (checked, no matches) — not a web-side gap.

## Testing

- Unit: consent state machine, transaction mapper against Setu sandbox fixtures for both FI types, webhook signature verification, the new fuzzy near-duplicate pass.
- Integration: full consent → webhook → fetch → `Transaction` round trip against Setu's sandbox AA+FIP.
- No real-bank testing is possible before a real user links a real account — sandbox is the ceiling until then.

## Explicitly out of scope / open items for a follow-up

- Exact current list of `CREDIT_CARD`-supporting issuers on Setu's network — verify against their live docs/sandbox before implementation, not asserted here.
- Whether a high-scoring AA-vs-manual fuzzy match should ever auto-confirm rather than flag for review — starts conservative (flag), same posture the existing Gmail fuzzy pass already takes.
- General account-identity matching (AA-linked account ↔ existing manually-imported account) reuses whatever matching Fynora already does for repeat-import account detection. If that turns out weaker than assumed here, it becomes its own sub-spec — this design does not re-solve identity resolution from scratch.
