# Bank from layout profile, and a required bank at import — design

Date: 2026-10-22
Status: draft, awaiting Sid's approval

## Why

Some statement uploads create an account with no bank. The account is named "Bank Statement Import"
and shows a generic bank, with no logo or name.

Evidence, from the admin portal's Layout Studio on 2026-10-22 (bank names have only been recorded
since 2026-09-29):

- Since 2026-09-29, 17 of 18 readable PDFs had a bank detected. Only 4 of 7 readable CSVs did.
- The 3 CSVs without a bank:
  - two ICICI credit-card exports (SA-20261022-0190 and 0191);
  - one HDFC savings export (SA-20261022-0194).
- The ICICI layout (FP-1-6D8FF624) was detected as ICICI on 2026-10-21 (SA-20261021-0184), so the
  same layout gives different answers depending on the file.

The cause is in `StatementValidator.buildDetectedAccountInfo` (CSV) and
`PdfPreviewGenerator` (PDF). Both call `BankRegistry.detect(filename, hints)`. For a CSV, the hints
are only the cells in the rows above the transaction header, plus the header row itself. A CSV with
no rows above its header, saved under a file name that does not name the bank, therefore has no bank
signal at all. Transaction rows are not used, on purpose: their IFSC codes belong to the other party,
not to the account holder's bank.

Today nothing lets the user fix this:

- The web import review shows "Detected <bank> from this statement" only when a bank was found. When
  none was found it shows nothing and sends `bankId` OTHER.
- The mobile import review never shows the bank.
- Neither web nor mobile can change an account's bank after it is created. The web has no account
  edit screen at all; the mobile `AccountFormSheet` leaves the bank out on purpose. The backend's
  account update (`AccountService.update`) already accepts `bankId`.

## Sid's decisions (2026-10-22)

1. **A wrong bank is worse than no bank.** No change may label an account with a bank unless
   something solid confirms it. Inferring the bank from the column names alone was considered and
   rejected for this reason.
2. **Fill the bank from the layout's profile.** When an upload's layout belongs to a profile, and that
   profile has a bank, use that bank when the file itself names none.
3. **Both kinds of profile link count:** layouts an admin attached by hand, and layouts the system
   grouped automatically.
4. **Ask the user when the bank is still unknown, and make it required (option B).** On the import
   review, when creating a new account, the user must choose a bank, or "Other bank", before Import
   is enabled.
5. **Users can change an account's bank later**, on web and mobile.
6. **No change to customer data by us.** Existing bankless accounts, including the one from
   SA-20261022-0194, are fixed by their owners through decision 5.

## Goals

- An upload whose layout has a profile with a bank gets that bank, even when the file names no bank.
- No new account is created without the user either confirming a bank or choosing "Other bank".
- A user can change the bank on any of their accounts, on web and on mobile.

## Non-goals

- Detecting the bank from column names, narration text, or IFSC codes inside transaction rows.
- Feeding a user's chosen bank back into a profile or into detection. One user's choice affects only
  their own account.
- Changing an account's type after creation (unchanged; `AccountService.update` never applies it).
- Backfilling or editing existing customers' accounts.

## Anti-goals (a failure even if it works)

- Any path that puts a bank on an account that neither the file, a profile, nor the user supplied.
- A profile overriding a bank the file itself names.
- A user who cannot finish an import because their bank is not in the list. "Other bank" must always
  be available.

## Design

### 1. A bank on each profile (backend + admin portal)

- `layout_profile` gets a nullable `bank_id` column (new Flyway migration; take the next free version
  on `origin/main` at the time, per the repo `CLAUDE.md`).
- Backfill in the same migration: for every profile with an `auto_key`, `bank_id` is the part of the
  key before `|` (the key is `bankId|families`, built by `LayoutIdentity.key()`). Profiles made by
  hand (no `auto_key`) stay null until an admin sets a bank.
- Profiles the auto-linker creates get `bank_id` from the `LayoutIdentity` that created them.
- Admin portal, Layout Intelligence, Profiles tab:
  - "Create profile" asks for a bank as well as a name. The bank is required for new profiles.
  - Existing profiles with no bank show "No bank set" and an action to set one.
  - The bank list is the same one the user-facing bank picker uses (`GET /api/v1/banks`, backed by
    `BankManagementService`), so an admin-added custom bank can be chosen too. (My call: detection
    stays built-in-only, as `BankController`'s comment requires, but a profile's bank is chosen by a
    person, the same as the manual picker, so the full list is safe here.)
- Admin API: `POST /admin/imports/layout-registry/profiles` takes `bankId`; the profile `PATCH` can
  set or change it. Both are audited like the existing profile edits.
- Changing a profile's bank affects future uploads only. Accounts already created keep their bank.

### 2. Filling the bank at import (backend)

Where: after detection and before the staging response is returned, in the one place every staging
path passes through with both the layout fingerprint and the detected accounts (the sync PDF and CSV
paths, the async job path via `StagedForJob`, and re-staging of held statements). The builder picks
the exact seam; `ImportService`, where `LayoutIdentity.of(...)` and `layoutReviewService.onStaged`
are already called with the fingerprint, is the likely one.

Rule, per detected account section:

1. The file names a bank (detection returned anything other than `OTHER`): keep it. The profile is
   not consulted for the bank.
2. Otherwise, look up the layout's fingerprint in `layout_registry`. If it has a `profile_id` (link
   source AUTO or MANUAL) and that profile has a `bank_id`, use that bank.
3. Otherwise the bank stays `OTHER`, and the user is asked (section 3).

When the bank comes from a profile:

- `DetectedAccountInfo` carries where the bank came from: `bankSource` = `FILE`, `PROFILE`, or `NONE`.
  This is for the review screen's wording and for diagnostics. It is not shown to admins as a
  customer value.
- The suggested account name follows the bank, the same way a detected bank does today
  (`StatementValidator.suggestedAccountName`).
- `matchExistingAccount` (web and mobile) treats it the same as a detected bank.
- The layout's own grouping is unchanged. In particular, a bank filled from a profile must not be fed
  back into `LayoutIdentity.of(...)` as if the file had named it. Otherwise a layout would
  confirm its own profile.

Conflict: when the file names bank X and the layout's profile has bank Y (X ≠ Y), the file's bank is
used (rule 1) and the layout is flagged for review with the existing `IDENTITY_CONFLICT` reason.

### 3. Required bank on the import review (web and mobile)

Applies only when the user is creating a new account. Importing into an existing account, and
re-importing, are unchanged.

- A "Bank" field is always shown in the new-account form, with search, using `GET /api/v1/banks`.
- Bank known (`bankSource` FILE or PROFILE): pre-filled. The hint says "Detected from this
  statement" (FILE) or "Recognised from this statement's format" (PROFILE). The user can change it.
- Bank unknown (`bankSource` NONE): empty and required. Import stays disabled until the user
  chooses a bank or "Other bank". A short line explains why.
- "Other bank" is an explicit choice at the end of the list and sends `bankId` OTHER.
- Choosing a bank fills the account name with the bank's name, unless the user has already typed a
  name of their own.
- Multi-account statements (several sections, each creating its own account) ask per section.
- The bank sent with the new account is the user's final choice, not the detected value.
- Mobile today does not show the bank at all; it gains the same field and rule.

### 4. Changing the bank later (web and mobile)

- Mobile: `AccountFormSheet` in edit mode gains the Bank field (same picker, "Other bank"
  included). Add mode stays as it is, unless the builder finds it shares the component naturally.
- Web: there is no account edit screen today. Add an "Edit account" action on the account (from the
  Accounts list or the account detail, whichever the web already uses to show one account) with name
  and bank. Name and bank are the only fields in scope.
- Backend: `AccountService.update` already applies `bankId`. Verify that it rejects an unknown id and
  that the bank change does not disturb anything keyed on the old bank (for example
  `matchExistingAccount`, or merchant/transfer logic that reads the account's bank). Report anything
  found.
- Accounts synced through Account Aggregator: whether their bank may be edited is left to the builder
  to check against how AA sets it. If editing would fight the sync, the field is read-only for them.

## Rollout and order

Suggested as three PRs, each releasable alone:

1. Backend + admin portal: profile bank, the fill rule, `bankSource`, conflict flag.
2. Web: required bank on import review, and the new edit-account option.
3. Mobile: the same two pieces. This ships in a new app binary, not an OTA update, if it needs any
   native change; otherwise the normal mobile release rules apply.

Until PR 2 and 3 ship, clients that ignore `bankSource` keep today's behaviour, and the filled bank
already reaches them through `bank`.

After PR 1 deploys, an admin sets a bank on every profile made by hand, which the migration leaves
empty. Known one: "ICICI Bank — Credit Card" (created 2026-10-21 for FP-1-6D8FF624). The Profiles tab's
"No bank set" marker lists the rest.

## Calls made by me, not Sid

- A detected or profile bank is pre-filled but still editable on the review screen.
- The profile bank can be any bank in `GET /api/v1/banks`, including admin-added ones.
- The web edit-account screen covers name and bank only.
- `bankSource` is added to `DetectedAccountInfo` so the clients can word the hint.

## Verification (per repo CLAUDE.md)

- Backend, at value level, over the real corpus (`/Users/sid/Downloads/Bank statement`), before and
  after: every file's detected bank, account type and rows must be identical. The fill rule only acts
  when detection returns OTHER **and** a profile exists, and the local corpus has no profiles, so any
  difference is a bug.
- Ground truth unchanged.
- Unit tests for the fill rule: file bank wins; profile bank used when the file has none; no profile;
  profile without a bank; AUTO link; MANUAL link; conflict raises `IDENTITY_CONFLICT`; a filled bank
  is not fed back into `LayoutIdentity`.
- An integration test that stages a synthetic CSV with the HDFC column set and no bank text, with the
  layout attached to a profile that has bank HDFC, and checks the staged `bank` and `bankSource`.
  Fixtures are synthetic only.
- Web and mobile: tests for the required field (disabled Import, "Other bank" enables it, a
  detected bank pre-fills), and for the edit-account bank change.
- After deploy: re-upload a synthetic HDFC-format CSV with no bank text in production through
  a test account and confirm HDFC is filled. The HDFC profile already contains FP-1-CA196976 since
  2026-10-22.

## Left to the builder

- The exact seam for the fill rule, provided every staging path goes through it.
- Exact wording of the hints and the "why" line, kept short and plain.
- Where the web's edit-account action lives, following the web's existing account screens.
- Whether the mobile add-account mode also gets the bank field.
