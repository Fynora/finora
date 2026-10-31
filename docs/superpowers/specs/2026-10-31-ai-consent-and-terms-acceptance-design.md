# AI consent switch and recorded Terms acceptance (privacy workstreams 7 and 8)

Date: 2026-10-31. Status: decisions below made by Sid on 2026-10-31; notice wording and the
withdrawal rules are drafts for counsel to review before final publication (workstream 11).

## Why

The privacy and Terms gap analysis of 2026-10-30 found:

- There is no way to turn off AI processing without deleting the account. AI category suggestions
  run automatically on a hand-added transaction.
- Consent to the Terms and Privacy Policy comes from a passive "By continuing you agree" notice,
  bundled with every optional purpose.
- Acceptance is one version and one timestamp on `users`, overwritten in place. 19 of 55 live
  accounts have none (created before V223).
- When the Terms change there is no way to ask users to accept the new version.

## Decisions (Sid, 2026-10-31)

| Question | Decision |
|---|---|
| AI default | **Off until the user says yes.** New users choose at sign-up (box not pre-ticked). Existing users are asked once. No AI call is made for a user who has not said yes. |
| Number of switches | **One** "AI features" switch: Ask Fyn, AI insights, AI category suggestions. |
| Sign-up control | **One required box**, not pre-ticked: "I am 18 or older and agree to the Terms of Service and Privacy Policy". The sign-up button stays disabled until it is ticked. A separate, optional AI box below it. |
| Asking existing users | **A full-screen step they must answer** on next open. Ways out: read the documents, sign out, or delete the account. |

## Data (V302)

- `users.ai_processing_consent BOOLEAN NULL` and `users.ai_processing_consent_at TIMESTAMPTZ NULL`.
  `NULL` means "not asked yet" and is treated as no. A CHECK constraint makes both columns null, or
  both set.
- `user_terms_acceptances`: an append-only history with one row per acceptance.
  - Columns: `id`, `user_id`, `terms_version`, `accepted_at`, `channel` (`WEB`, `MOBILE_IOS`,
    `MOBILE_ANDROID` or `UNKNOWN`) and `method`.
  - `method` is `CHECKBOX` (the new, affirmative tick) or `NOTICE` (the old passive notice).
  - The migration backfills one `NOTICE` row with channel `UNKNOWN` for every user who already has
    `terms_version`. The history therefore starts with what was actually recorded and claims nothing
    more.
- `users.terms_version` and `users.terms_accepted_at` stay. They hold the latest acceptance, so
  existing readers keep working.
- **No IP address is stored.** Version, channel and time are enough to show which document was
  accepted, and storing less personal data is the safer default. If counsel wants the IP, it is one
  column.
- **Account deletion keeps the acceptance rows.** The `users` row is anonymised, not deleted, so the
  rows keep pointing at an anonymised account. They show that consent was given, which may be needed
  later. The retention register (workstream 9) gets a line for them, for counsel to confirm.

## When the consent step is shown

`consentRequired` = the latest acceptance is not `LegalTerms.CURRENT_VERSION`, **or**
`ai_processing_consent` is null.

- **The 19 accounts with no acceptance:** see the whole step (notice, required box, AI choice).
- **The 36 that accepted the current version by notice:** this counts as accepted, so they see the
  notice and the AI choice only, with no required box.
- **When workstream 11 bumps `CURRENT_VERSION`:** everyone sees the required box again.
- **The AI choice is answered by submitting the step.** A ticked box records yes; an unticked box
  records no. That is an answer, so the user is not asked again. The Settings switch changes it at
  any time.

## API

- **`GET /api/v1/users/me` (`UserSettingsDto`) gains:**
  - `termsAcceptanceRequired` (boolean)
  - `currentTermsVersion` (string)
  - `aiProcessingConsent` (boolean or null)
- **New: `POST /api/v1/users/me/consent`** with body `{ acceptTermsVersion?: string, aiProcessing?: boolean }`.
  - `acceptTermsVersion` must equal `CURRENT_VERSION`. Anything else gets a 409: the client showed a
    document that is no longer current, so it has to reload and show the new one.
  - Accepting adds a `CHECKBOX` row. The channel comes from `X-Client-Platform`, which is advisory:
    it is used for the record only, never for authorisation. Accepting also updates
    `users.terms_*` and writes the audit row `TERMS_ACCEPTED {version, channel}`.
  - Setting `aiProcessing` writes the audit row `AI_PROCESSING_CONSENT_CHANGED {enabled, channel}`.
    Turning it from yes to no also erases that user's stored AI output (see Withdrawal) and writes
    `AI_OUTPUT_ERASED {aiInsightTextsDeleted, aiAuditPayloadsCleared}`.
  - Error codes: `PRIVACY_001` (AI features off, 403), `PRIVACY_002` (Terms version outdated, 409),
    `PRIVACY_003` (required sign-up box unticked, 400).
  - The Settings switch uses the same endpoint, sending `aiProcessing` only.
- **`RegisterRequest` gains `acceptedTerms` and `aiProcessingConsent`, both optional booleans:**
  - `acceptedTerms = true`: the request records a `CHECKBOX` acceptance.
  - `acceptedTerms = false`: the request is rejected (400).
  - `acceptedTerms` absent: the request comes from an app released before this change. **No
    acceptance is recorded**, because that user saw only the passive notice. The consent step asks
    them the next time they use web or an updated app.
- **Google or Apple sign-in that creates a new account:** no acceptance is recorded at creation any
  more. The consent step appears right after the first sign-in, so these users tick the same box.

## AI gate

`FynAvailabilityGuard.chatAvailableFor`, `insightsAvailableFor` and `categorizationAvailableFor`
also require `ai_processing_consent = true`. That covers every per-user path that calls Anthropic:
Ask Fyn, AI insights, and both category-suggestion services.

- **Ask Fyn:** with the switch off, `preflightChat` returns a new error code, `FYN_AI_CONSENT_OFF`
  (403). The apps then show "AI features are off" with a link to Settings, instead of a generic
  outage message.
- **AI insights:** the new reason `AI_CONSENT_OFF` falls back to the rules-written text. Stored AI
  text is not served while the switch is off.
- **Category suggestions:** already fall back to rules when the guard says no. Nothing changes.
- **Admin import diagnosis (`importAssistAvailable`):** not covered. An admin triggers it, and the
  gap analysis found it sends no customer content. It is listed here so the choice is visible.

## Withdrawal (switch turned off)

1. New AI calls stop at once, through the guard.
2. The AI insight texts written for the user (`ai_insight_texts`) are deleted. They were produced
   under the consent being withdrawn. The switch warns about this before it turns off.
3. In `ai_audit_log`, the facts sent to Anthropic and returned (`tool_inputs`, `tool_outputs`) are
   cleared. The rows stay, because they are the operator's record of calls made and their cost, and
   the per-user cost cap reads them.
4. **Ask Fyn conversations stay.** They are the user's own record, shown only to them, and nothing
   in them is sent anywhere again. They also hold the Free plan's daily question count: deleting
   them would hand back that day's questions, so switching off and on again would get around the
   limit. Counsel to confirm. If counsel requires deletion, the daily count needs its own counter
   first.
5. A first answer of no erases nothing, because no output was produced under a consent.
6. Turning it back on starts fresh.

## Clients

Web ships in the same PR as the backend. When the backend deploys, AI is off for everyone until
they answer, so the web step has to exist on day one. Mobile follows in its own PR and ships in the
1.2.0 store build.

- **Sign-up (email, web and app):**
  - The passive notice is replaced by the required box and the optional AI box.
  - The button is disabled until the required box is ticked.
  - The request sends `acceptedTerms: true` and `aiProcessingConsent`.
- **Consent step:** a full screen inside the signed-in app, before any other screen. Data is held
  back while it loads, so no screen shows briefly and then disappears. It contains:
  - the notice: what Fynora collects, why, how to withdraw each optional purpose, the grievance
    contact, and the right to complain to the Data Protection Board of India
  - the required box (only when `termsAcceptanceRequired`)
  - the AI box
  - "Continue"
  - links to the Terms and the Privacy Policy, "Sign out" and "Delete account"
- **Settings:** an "AI features" switch in the Data section (web `DataPane`, mobile
  `SettingsDataScreen`). It has a one-line description of what is sent to Anthropic, and the
  withdrawal warning when turning it off.
- **Ask Fyn:** on `FYN_AI_CONSENT_OFF` it shows the message with a link to Settings → Data.
- **AI insights:** no change. With the switch off the backend returns the rules-written text, as
  it already does for anyone outside the AI audience.

## Known limits (stated, not fixed here)

- **The backend does not block API calls when consent is missing.** The step is enforced in the
  client. The 1.1.x apps cannot show it, so a user on an old app keeps using the app with AI off
  until they update. Blocking on the server would break those apps outright.
- **Accounts created from a 1.1.x app after this deploys have no acceptance** until the user updates
  or uses web. That is accurate, because they never ticked a box.
- **The notice is English only.** The DPDP point about Eighth Schedule languages on request is a
  documented process (workstream 6 and 11), not code.
- **The policy text itself does not change here.** Workstream 6 rewrites the consent paragraph to
  describe this switch.

## Tests

- **Migration integration test:**
  - the backfill creates one `NOTICE` row per user with a version, and none for a user without one
  - the CHECK constraint rejects one consent column without the other
- **Consent service tests:**
  - the version mismatch returns 409
  - each submission adds a row, never an update
  - the channel comes from the header
  - audit rows are written
  - withdrawal deletes the user's AI texts and conversations, and no other user's
- **Guard tests:** all three per-user checks are false when consent is null or false, and true only
  when it is true.
- **Register and OAuth:** with the field absent, no acceptance is recorded; `true` records one;
  `false` is rejected; OAuth creation records none.
- **Insights and chat:** the consent-off reason falls back to rules text; stored AI text is not
  served; chat returns the new code.
- **Web and mobile:**
  - sign-up button disabled until ticked
  - consent step shown and hidden by the three profile fields
  - Settings switch warning and call
  - Ask Fyn consent-off state
