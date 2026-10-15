# Scan & Pay — Part 1: Scan, categorize, hand off

**Status:** v3 approved by Sid on 2026-10-15. Not built.
**Date:** 2026-10-15
**Covers:** Part 1 of 3. Part 2 (attach the label to the statement row) and Part 3 (swap the hand-off for Fynora's own UPI payment once TPAP is approved) are separate drafts.

---

## 1. Goal

Make Fynora the app people open every time they pay. The user scans a UPI QR code in Fynora, picks a category before paying, and the payment completes.

The plan has two tracks:

- **Track A (this document):** build scan-and-categorize now. Payment is handed off to an installed UPI app (GPay, PhonePe, Paytm). No licence is needed.
- **Track B (business, in parallel):** apply to become a Third Party Application Provider (TPAP) with a sponsor bank. When approved, only the "pay" step changes. The rest of Part 1 is reused as-is.

## 2. What Part 1 includes, and what it leaves out

**In scope:**
- Scanning a UPI QR code with the phone camera.
- A confirm screen with payee, amount and a pre-selected category.
- Saving a "payment plan" record on the backend.
- Handing the payment to a UPI app and coming back.

**Out of scope for Part 1:**
- Matching the plan to a bank statement row. That is Part 2.
- Fynora moving money itself. That is Part 3, after TPAP approval.
- Web. Scanning is mobile-only.

## 3. What already exists (checked on `origin/main`, 286b30d7e)

- **Payee identity from statements.** `Transaction.counterpartyKey` holds `vpa:<local-part>` when the statement narration carries a UPI ID (`backend/.../util/CounterpartyIdentity.java`). The part after "@" is dropped on purpose, so one person on `@ybl` and `@paytm` counts as one payee. A scanned QR gives the full UPI ID, so the same key can be built from it.
- **Weak keys when banks cut the ID.** Some banks print only part of the UPI ID. `CounterpartyIdentity` records 18 PNB and Canara rows in the corpus where the ID was cut before the "@", which fell back to weaker `cut:` / `name:` keys. A scan carries the full ID, which Part 2 can use to firm these up.
- **Saved category choices per payee.** `CounterpartyCategoryObservation` stores a user's category correction against a `vpa:`-keyed payee.
- **Category rules by payee.** `CategoryRule` already supports `Field.PAYEE` with `payeeAliases`.
- **No camera or QR code in mobile today.** `mobile/package.json` has no `expo-camera` or similar, and no `upi://` handling exists in `mobile/src`. Mobile runs Expo SDK 57 and React Native 0.86.3.
- **Navigation rules.** Any new destination must follow the approved nav spec (`docs/superpowers/specs/2026-09-23-shared-nav-taxonomy-design.md`, "Governance"). See section 9.

## 4. The flow

1. **Open the scanner.** The camera asks for permission the first time.
2. **Read the QR.** Parse it (section 5). If it is not a UPI payment QR, say so and stay on the scanner.
3. **Confirm screen** shows:
   - The name on the QR, labelled "Name on QR". It is not verified by Fynora; the UPI app verifies the payee.
   - The UPI ID.
   - The amount. It is locked if the QR fixes it, otherwise the user types it.
   - One pre-selected category (section 6), which the user can change.
   - An optional note.
4. **Save the payment plan** on the backend (section 7), **before** the hand-off. The phone creates the plan's id, so a retry after a timeout can never make two plans.
   - Paying needs the internet anyway, so a save failure usually means Fynora's backend is having trouble, not that the phone is offline.
   - If the save fails, show "Couldn't save the category" with two choices: **Try again**, or **Pay without saving**. The second still hands off, and nothing is recorded. The statement row will arrive uncategorized as it does today.
   - No hidden queue on the phone. A plan is either saved before the hand-off, or the user knowingly paid without one.
5. **Hand off** to a UPI app (section 8). The user taps **Pay ₹<amount> to <name on QR>** on the confirm screen to start it. Fynora never opens the UPI app without that tap.
6. **Come back.** Show "Payment sent to <app>". Any success or failure the app reports is shown as what the app said, not as fact. The bank statement is the only proof that money moved.

A payment plan **never creates a transaction.** It is a label waiting for the statement row. This follows the standing rule that nothing is recorded until the bank statement confirms the amount, and it avoids the duplicates that led to refusing UPI app histories (IMPORT_018).

## 5. Reading the QR

**UPI link form** (`upi://pay?...`). Fields used:

| Field | Meaning | Rule |
|---|---|---|
| `pa` | Payee UPI ID | Required. Reject the QR without it. |
| `pn` | Payee name | Optional. Shown as "Name on QR". |
| `am` | Amount | Optional. If present, lock it. |
| `cu` | Currency | If present it must be `INR`, otherwise reject. |
| `mc` | Merchant category code | Optional. `0000` or missing means a person, not a shop. |
| `tn` | Note from payee | Optional. Shown read-only. |
| `tr` | Payee's reference | Optional. Passed through to the UPI app unchanged. |

**The QR is untrusted input.** Validation rules:

- Only `upi://pay` is accepted. Any other scheme or path is rejected.
- **Duplicate keys are rejected.** If a QR has two `pa` or two `am` values, Fynora might show one while the UPI app uses the other. That mismatch is the attack, so such a QR is refused outright.
- Each value is percent-decoded exactly once.
- `pa` must look like `<local-part>@<handle>`, with a bounded length and allowed characters only.
- `am`, when present, must be a positive decimal with at most 2 decimal places. The upper limit is left to the UPI app, which enforces the current NPCI limits.
- `pn` and `tn` are shown with control characters removed and a length cap. They are always labelled as text from the QR, never as verified.

**What is sent to the UPI app: the original QR text, unchanged.** It is not rebuilt from the fields Fynora understood. The one exception is the amount: when the QR has no `am` and the user types one, `am` (and `cu=INR`) is added.

Why not rebuild a clean link from allowed fields only? Merchant QRs can carry fields the UPI app uses to treat the payee as a verified merchant, for example a signature. Rebuilding could drop them and change how the app treats the payment. **Not established:** which fields the apps actually rely on, and whether adding `am` to such a QR breaks it. Phone test T6 checks this.

**Forwarding policy (closed list).** The original text is forwarded only if **every** field in it is on a known list. Otherwise the QR is refused. Nothing unknown is passed on, and nothing is silently dropped.

- **The known list** is the core fields in the table above, plus the merchant fields named in NPCI's UPI link specification.
- **Not established:** the exact merchant field names. They must be read from NPCI's current specification before implementation, not guessed. T6 confirms them against real QRs.

Rules so that Fynora and the UPI app read the same values:
- Field names are compared ignoring case, so `pa` together with `PA` counts as a duplicate and is refused.
- Leading or trailing spaces, and invisible or control characters, anywhere in `pa` or `am`, cause a refusal. They are not trimmed.
- `+` in a value is treated as a literal plus, not a space.
- `pa` may contain only letters, digits, `.`, `-`, `_` and one `@`, so look-alike characters from other alphabets are refused.

**Even if Fynora and the app disagree, the money is still protected.** The UPI app shows its own payee name and amount before asking for the UPI PIN, and that screen is what the user approves. A disagreement would therefore put a wrong label on the plan, not send money to the wrong person. It is still treated as a defect. T6 checks, for every sample QR, that the payee and amount Fynora shows match what the app shows.

**Bharat QR / EMV form** (a long string starting `000201...`). Many shop counters show this kind. It carries UPI details inside a nested field. **Not established:** how many real QRs use this form. The phone tests (section 11) include photographing real counter QRs to decide whether Part 1 must read it.

The parser is a small pure function with no camera dependency, so it can be unit-tested on its own.

## 6. Pre-selected category

Picked in this order, first match wins:

1. **Last choice for this payee.** The user's own most recent category for the same `vpa:<local-part>` key, from earlier scans or statement corrections.
2. **The user's category rules** that match this payee.
3. **Merchant code** from `mc`, through a fixed table from merchant code to Fynora category. The table is written by hand and reviewed. Codes not in the table give nothing.
4. **A person, not a shop** (`mc` is `0000` or missing): pre-select "Paid a Person" and ask what it was for.
5. **Nothing.** The user must pick.

The user's choice from this screen is stored with the plan. It is also a strong signal for future scans of the same payee.

## 7. The payment plan record (backend)

New table, working name `scan_payment_plans`.

| Column | Notes |
|---|---|
| `id` | UUID, created on the phone. Saving the same id twice returns the existing plan, so retries are safe. |
| `user_id` | Owner |
| `payee_vpa` | Full UPI ID from the QR |
| `counterparty_key` | `vpa:<local-part>`, built by the same code as statements so keys match exactly |
| `payee_name_on_qr` | Shown as unverified |
| `amount` | Decimal, INR |
| `amount_locked` | True if the QR fixed it |
| `merchant_code` | `mc`, nullable |
| `category` | User's final choice |
| `category_source` | `LAST_CHOICE`, `RULE`, `MERCHANT_CODE`, `PERSON_DEFAULT`, `USER_PICKED` |
| `note` | Optional |
| `handed_off_to` | App package or scheme, nullable |
| `app_reported_status` | `SUCCESS`, `FAILURE`, `SUBMITTED`, `UNKNOWN`. A hint, never proof. |
| `app_response_refs` | Raw reference values returned by the UPI app, if any. Stored exactly as received. |
| `handed_off_at` | When the UPI app was opened. Part 2's date window starts here. |
| `hidden_by_user` | Display only. Never affects matching. |
| `status` | `PLANNED`, `HANDED_OFF`, `CANCELLED`, `EXPIRED` (Part 2 adds `MATCHED`) |
| `created_at`, `updated_at` | |

**Allowed status changes** (anything else is refused by the backend):

| From | To | When |
|---|---|---|
| `PLANNED` | `HANDED_OFF` | User tapped Pay and the UPI app opened |
| `PLANNED` | `CANCELLED` | User backed out before paying |
| `HANDED_OFF` | `EXPIRED` | No statement match within the expiry window (open question 1) |
| `HANDED_OFF` | `MATCHED` | Part 2 |

`app_reported_status` can be set any time after `HANDED_OFF`. It never changes `status`.

Two separate questions are kept in two separate fields:
- `status` answers "where is this plan in Fynora's flow?"
- `app_reported_status` answers "what did the UPI app say happened?"

`CANCELLED` is reachable **only** before the hand-off. Once the UPI app has opened, Fynora cannot know whether money moved. Even if the user says "I didn't pay", the plan stays `HANDED_OFF` until the statement matches it or it expires. The user can hide it from their list. Hiding changes only what is shown, never `status`, so a payment the user forgot about still gets its category when the statement arrives.

**Retries with the same id:**
- If the plan id already exists and every field matches, return the existing plan unchanged.
- If the plan id already exists but any field differs (amount, payee, category, note), refuse with a conflict error and leave the stored plan unchanged.

Changing the category or note later (open question 5) is a separate, explicit edit request. It is never a re-save.

**Reference numbers from the UPI app.** On Android, a UPI app may return reference values after paying (field names such as `txnId`, `txnRef`, `ApprovalRefNo`). They are stored exactly as received in `app_response_refs`, never interpreted in Part 1. If one of them appears in the bank statement's narration, it is the strongest match Part 2 could use. **Not established:** whether any of them does. T4 checks this.

**Which payee field is used for what:**
- `counterparty_key` (local part only) is used **only** to suggest a category (section 6). Two different people can share a local part, for example the same name on two providers. In that case the worst outcome is a wrong suggestion the user can see and change.
- `payee_vpa` (full ID) is kept for Part 2 matching. Part 2 must not match on the local part alone. How often two payees share a local part has not been measured.

The Flyway version number is chosen at implementation time, after fetching `origin/main` and listing the migration folder, as the repository rules require.

**Unmatched plans.** The expiry window and how long unmatched plans are kept are open questions (section 12).

## 8. Handing off to a UPI app

- **Android:** open the `upi://pay?...` link with the system app picker, so the user picks GPay, PhonePe or Paytm. Read the result the app sends back if any.
- **iOS:** iOS has no shared app picker for `upi://`. Each app has its own link format (for example `tez://`, `phonepe://`, `paytmmp://`), which Fynora must declare in `LSApplicationQueriesSchemes`. Show only the apps that are installed.
- **Fallback if hand-off does not work on a platform:** scan, categorize and save the plan, then tell the user to pay in their UPI app themselves. The plan is still matched in Part 2.

**Not established, must be tested on real phones (section 11):**
- Whether each app completes the payment when opened from Fynora, both to a shop and to a person. Some apps reportedly block payments to a person opened this way.
- Whether iOS links open with the amount pre-filled.
- Whether a result comes back, and how reliable it is.

## 9. Where the Scan button goes

Per the nav spec's Governance rule, a new destination needs:

1. Exactly one home group. **Proposed: Money** (next to Accounts and Transactions), added to both `mobile/src/navigation/taxonomy.ts` and `frontend/src/navigation/taxonomy.ts` in the same PR. On web the item would need an explanation screen instead of a scanner.
2. A written reason for any shortcut.

**Proposed shortcut:** a "Scan & Pay" row in the centre-button sheet (`QuickActionSheet`, today Import Statement / Add Transaction / Add Goal). **Reason:** the goal is to be opened at every payment, so Scan must be one tap from anywhere.

**Open question:** whether Scan should replace the centre button's first action, or get its own tab. Either one changes the tab bar, which the spec treats as a frequency-based promotion. Usage data from the Track A launch would justify it.

## 10. Safety, privacy and errors

**Safety:**
- Label the name as "Name on QR", never as verified.
- Never pre-fill an amount the QR did not fix.
- Any QR that is not a `upi://pay` link (or a Bharat QR carrying UPI details, if section 5 adds it) is rejected.

**Privacy:** production has real customers. The UPI ID and payee name are personal data. The same data already reaches the backend through statement narrations, but this table is a new place it lives. It must be deleted with the user's account and never appear in logs. Not yet checked: how account deletion and data export find user tables today. That needs reading before implementation, so this table is not missed.

**Errors:**

| Case | What the user sees |
|---|---|
| Camera permission denied | Explanation plus a link to Settings |
| QR is not UPI | "This isn't a UPI payment code" |
| QR is broken or missing `pa` | Same message, scanner stays open |
| No UPI app installed | Plan saved, user told to pay in any UPI app |
| User backs out of the UPI app | Plan stays `HANDED_OFF`, app result `UNKNOWN` |
| Plan save fails or times out | "Couldn't save the category": Try again (same plan id, no duplicate) or Pay without saving |
| App is killed while the user is in the UPI app | On reopen, a plan still `HANDED_OFF` is shown as "Payment to <name>: waiting for your statement". No guess about the outcome. |

## 11. Phone tests before the spec is final (throwaway build)

Sid runs these on real phones with UPI set up. Emulators cannot complete UPI payments.

| # | Test | Platforms | Decides |
|---|---|---|---|
| T1 | Pay a shop QR via each app, opened from Fynora | Android, iOS | Whether hand-off works for shops |
| T2 | Pay a person QR (small amount) the same way | Android, iOS | Whether hand-off works for people, or is blocked |
| T3 | Does the amount arrive pre-filled? | iOS | Whether the iOS per-app links are usable |
| T4 | What result comes back on success, failure and cancel. Then, when that month's statement arrives, check whether any returned reference value appears in the payment's narration. | Android, iOS | Whether `app_reported_status` is worth storing, and whether Part 2 gets an exact match key |
| T5 | Photograph 20+ real counter QRs and record their form | n/a | Whether Bharat QR / EMV reading is needed in Part 1 |
| T6 | On a merchant QR with extra fields (for example a signature), compare paying with the original text against a rebuilt link, and test adding `am` to a QR without one | Android, iOS | Whether forwarding the original text unchanged is required (section 5) |
| T7 | Record which app versions and OS versions were tested | Android, iOS | The supported list shipped with Part 1. Anything untested is listed as untested. |

The test build is thrown away afterwards. None of its code is kept.

## 12. Open questions

1. How long before an unmatched plan expires? It depends on how often users import statements. Part 2 measures this.
2. How long are expired plans kept, and are they shown to the user?
3. Scan as a centre-button row, or its own tab (section 9)?
4. Does the confirm screen show budget status ("Food: 82% of this month used")? It is a strong differentiator, but adds a budget read to the scan path. Proposed for Part 1, needs a yes.
5. Can the note and category be edited after hand-off and before the statement arrives? Proposed yes.

## 13. Testing approach (for implementation)

- **Parser unit tests:** every field rule in section 5, plus broken and non-UPI inputs. Test QR strings use the synthetic placeholder forms the repository's data guard accepts, never real UPI IDs.
- **Category pre-selection tests:** each step of section 6, including the fall-through to nothing.
- **Backend tests:**
  - Creating a plan.
  - Users can see only their own plans.
  - `counterparty_key` equals what `CounterpartyIdentity` produces for the same UPI ID in a statement narration. This is the link between the two.
- **Mobile screen tests:** scanner states, confirm screen, the error cases in section 10.
- **On-device check** on iOS and Android before merge.
- **Parser attack cases:** duplicate keys, double-encoded values, overlong fields, control characters in `pn`, negative or 3-decimal amounts, and a non-`pay` path.
- **Retry tests:**
  - Saving the same plan id twice gives one row.
  - Saving the same id with a different amount is refused, and the stored row is unchanged.
- **Status tests:** every change not listed in section 7's table is refused, including `HANDED_OFF` to `CANCELLED`.

## 13b. What Part 1 must store for Part 2

Part 2 (matching a plan to a statement row) is designed separately. Part 1's table must already hold everything Part 2 could need, because a plan's data can't be recovered later:

| Part 2 input | Stored in Part 1 as |
|---|---|
| Amount | `amount` |
| Date window start | `handed_off_at` |
| Full payee UPI ID (exact comparison when the narration has it) | `payee_vpa` |
| Payee key (supporting signal only) | `counterparty_key` |
| Possible exact reference | `app_response_refs` |
| Payee name (weak, for narrations with no UPI ID) | `payee_name_on_qr` |

**Not known at scan time:** which bank account the user pays from. The UPI app chooses that. Part 2 must search all of the user's accounts.

Confidence levels, what happens with two equal payments to the same person, and manual review all belong to Part 2's design. Part 1 only commits to never auto-attaching a plan when more than one row could match. That rule follows from the standing rule that a guess is never recorded as fact.

## 13a. Release

- **Behind a feature flag.** The backend already has feature flags (`FeatureFlag` entity, `AdminFeatureFlagController`). Not yet checked: whether mobile reads them today. If it does not, that is part of the build.
- **Rollout:** testers first, then everyone, once T1–T7 pass on the supported list.
- **Monitoring** uses counts only, never UPI IDs or names: scans, rejected QRs by reason, saves, save failures, hand-offs, and plans by app-reported status.
**Release gates.** "Fixed" gates follow from the design. "Proposed" gates are starting numbers for Sid to confirm, not measured.

Tester phase, before release to everyone:

| Gate | Limit | Kind |
|---|---|---|
| Duplicate plans (same id twice) | 0 | Fixed: the retry rule makes any duplicate a bug |
| Payee or amount shown by Fynora differs from the UPI app (T6) | 0 | Fixed |
| Crashes in the scan flow | 0 | Fixed |
| T1–T7 done on every platform listed as supported | All | Fixed |

First 2 weeks after release, turn the flag off if any of these happens:

| Signal | Turn-off limit | Kind |
|---|---|---|
| Duplicate plans | Any | Fixed |
| Crash in the scan flow | Any crash with no fix ready | Fixed |
| Plan save failures | More than 2% of save attempts in a day | Proposed |
| Hand-off failures (no UPI app opened) | More than 5% of hand-offs in a day | Proposed |
| Rejected QRs | No limit before T5 | Depends on how many real QRs use Bharat QR, which is unknown until T5 |

## 14. Track B: TPAP application (Sid, no code)

- Ask Juspay for a demo and pricing of their TPAP stack. Ask which sponsor banks are live on it and who owns the NPCI relationship.
- Shortlist 2–3 sponsor banks.
- Confirm whether the LLP qualifies. Vendor guides mention 2–3 years of audited financials; this is not verified against NPCI's current rules.
- Known ongoing duties: payment data stored only in India, and RBI and NPCI audit access to systems and data.
