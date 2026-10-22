# Bank from layout profile — PR 1 (backend + admin portal) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give layout profiles a bank, fill an upload's bank from its layout's profile when the file names none, tell clients where the bank came from, and give admins counts of what users chose.

**Architecture:** A `bank_id` column on `layout_profile` (backfilled from `auto_key`) feeds a small `ProfileBankFiller` that `ImportService` runs on every new-session staging path, after detection and before the session is stored. Layout identity and analysis evidence keep using the file-only detection. At confirm, one row per newly created account goes into a user-free `import_bank_choice` table, which two admin read endpoints aggregate.

**Tech Stack:** Java 21 / Spring Boot / JPA + JdbcTemplate / Flyway / PostgreSQL; React + TypeScript + TanStack Query + Vitest (admin portal).

**Spec:** `docs/superpowers/specs/2026-10-22-bank-from-profile-and-required-bank-design.md` (sections 1, 2, 5, 6, 8 and the PR 1 rollout line).

## Global Constraints

- Repo `CLAUDE.md` applies: work only in `/Users/sid/Downloads/finora-bank-from-profile`, no AI co-author trailer in commits, evidence-only claims.
- Flyway: before adding the migration, `git fetch origin` and list `backend/src/main/resources/db/migration`; take the next free version (V284 at time of writing). Never edit an existing migration.
- A bank filled from a profile never feeds `LayoutIdentity.of(...)`, `DocumentIdentity`, or the analysis row. Those stay file-only.
- The file's own bank always wins over a profile's.
- `OTHER` is never a profile bank and never counts as a match.
- Admin surfaces show counts only: no user id, account, file name, or value. `import_bank_choice` has no user column.
- Fixtures are synthetic. Use account-number placeholders like `100000000001` (data guard).
- Commit scope must be one of the commitlint scopes, e.g. `imports`, `admin-portal`, `db`.

## Review Focus

1. A session staged before deploy and confirmed after: its stored `detectedAccountJson` has no `bankSource`. It must still deserialize, and the confirm must still work (Task 3 round-trip test).
2. A profile whose `bank_id` names a custom bank an admin later deleted: `BankManagementService.resolve` falls back to OTHER. Treat it as "profile has no bank", not as a fill with OTHER (Task 3 test).
3. A layout whose profile bank equals the file's bank: no conflict reason, `bankSource` FILE (Task 4 test).
4. A multi-section PDF where one section names a bank and another does not: fill per section. The layout identity must still be computed from the unfilled sections (Task 4 test).
5. A confirm whose `newAccount.bankId` is unknown: the recorded `chosen_bank_id` must be the id the account actually got, not the raw request value (Task 5 test).

---

### Task 1: Store a bank on each profile

**Files:**
- Create: `backend/src/main/resources/db/migration/V284__layout_profile_bank_and_bank_choices.sql` (renumber if taken)
- Modify: `backend/src/main/java/com/finora/entity/LayoutProfile.java`
- Modify: `backend/src/main/java/com/finora/imports/LayoutProfileAutoLinker.java:148-162` (`profileFor`)
- Test: `backend/src/test/java/com/finora/imports/LayoutProfileAutoGroupingIT.java`

**Interfaces:**
- Produces: column `layout_profile.bank_id VARCHAR(64) NULL`; table `import_bank_choice(id UUID PK, layout_fingerprint VARCHAR(24) NULL, detected_bank_id VARCHAR(64) NOT NULL, bank_source VARCHAR(8) NOT NULL CHECK IN ('FILE','PROFILE','NONE'), chosen_bank_id VARCHAR(64) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now())` with an index on `(layout_fingerprint)` and one on `(created_at)`.
- Produces: `LayoutProfile.getBankId(): String`, `LayoutProfile.setBankId(String bankId): void` (updates `updatedAt`).

- [ ] **Step 1: Write the migration.** `ALTER TABLE layout_profile ADD COLUMN bank_id VARCHAR(64);` then the backfill `UPDATE layout_profile SET bank_id = split_part(auto_key, '|', 1) WHERE auto_key IS NOT NULL AND split_part(auto_key, '|', 1) <> 'OTHER';`, then `CREATE TABLE import_bank_choice ...` as above. Comment the file: why the table has no user column (counts only).

- [ ] **Step 2: Write the failing tests in `LayoutProfileAutoGroupingIT`.**
  - `autoCreatedProfileCarriesTheIdentitysBank`: link a fingerprint with `new LayoutIdentity("HDFC", "HDFC Bank", Set.of("SAVINGS"))`; assert `SELECT bank_id FROM layout_profile WHERE auto_key = 'HDFC|SAVINGS'` = `"HDFC"`.
  - `adoptingAHandMadeProfileByNameFillsAnEmptyBank`: insert profile "HDFC Bank — Savings / Current" with null bank and null auto_key; link as above; assert bank_id = `"HDFC"`.
  - `adoptingByNameNeverOverwritesABankAnAdminSet`: same, but the hand-made profile already has bank_id `"ICICI"`; assert `profileFor` does not adopt it (returns CONFLICT) and bank_id stays `"ICICI"`.

- [ ] **Step 3: Run them and see them fail.**
  Run: `cd backend && ./mvnw -q verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=LayoutProfileAutoGroupingIT`
  Expected: FAIL (no `bank_id` column / null bank).

- [ ] **Step 4: Implement.** Map `bank_id` on `LayoutProfile`. In `profileFor`: the INSERT also writes `identity.bankId()`; the name-adoption UPDATE becomes `... SET auto_key = ?, bank_id = COALESCE(bank_id, ?) ... WHERE lower(name) = lower(?) AND auto_key IS NULL AND (bank_id IS NULL OR bank_id = ?)`.

- [ ] **Step 5: Run the IT and see it pass; also run `LayoutReviewAndProfilesIT` and `LayoutRegistryIT` for regressions.**

- [ ] **Step 6: Commit.** `git commit -m "feat(imports): store a bank on each layout profile"`

### Task 2: Admins set a profile's bank

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/LayoutCurationService.java` (`ProfileView`, `profiles()`, `createProfile`, `renameProfile` → `updateProfile`)
- Modify: `backend/src/main/java/com/finora/controller/AdminLayoutRegistryController.java:97-108`
- Test: `backend/src/test/java/com/finora/imports/LayoutReviewAndProfilesIT.java`

**Interfaces:**
- Consumes: `LayoutProfile.getBankId/setBankId` (Task 1); `BankManagementService.resolve(String): BankDto`.
- Produces: `ProfileView(UUID id, String name, boolean automatic, String bankId, String bankName, List<RegistryEntry> versions)`; `createProfile(UUID actingAdminId, String name, String bankId): ProfileView`; `updateProfile(UUID actingAdminId, UUID profileId, String name, String bankId): void` (either may be null, not both); `POST /profiles` body `{name, bankId}`; `PATCH /profiles/{id}` body `{name?, bankId?}`.

- [ ] **Step 1: Write the failing tests.**
  - `createProfileRequiresAKnownBank`: POST with no `bankId` → 400; with `bankId` `"NOT_A_BANK"` → 400; with `"OTHER"` → 400; with `"HDFC"` → 200 and the view has `bankId "HDFC"`, `bankName "HDFC Bank"`.
  - `patchSetsTheBankOnAHandMadeProfile`: profile created with null bank directly in SQL; PATCH `{bankId: "ICICI"}` → profiles list shows `bankId "ICICI"`; an audit row `LAYOUT_PROFILE_BANK_SET` with the old and new ids exists.
  - `patchRenameStillWorks`: PATCH `{name: "x"}` renames without touching the bank.
- [ ] **Step 2: Run, see them fail.** Same command shape as Task 1 with `-Dit.test=LayoutReviewAndProfilesIT`.
- [ ] **Step 3: Implement.** A bank id is valid when `resolve(id).id().equals(id)` and `id` is not `OTHER`; otherwise 400 "Choose a bank for this profile". `profiles()` fills `bankName` from `resolve(bankId).officialName()` (null when no bank). The PATCH keeps the existing rename audit and adds `LAYOUT_PROFILE_BANK_SET`. Update `BankController`'s class comment: detection stays built-in-only, a profile's bank is chosen by a person from the full list.
- [ ] **Step 4: Run the IT; pass.**
- [ ] **Step 5: Commit.** `feat(imports): admins choose a bank for each layout profile`

### Task 3: `bankSource` and the filler

**Files:**
- Modify: `backend/src/main/java/com/finora/dto/ImportDto.java:341` (`DetectedAccountInfo`)
- Create: `backend/src/main/java/com/finora/imports/ProfileBankFiller.java`
- Test: `backend/src/test/java/com/finora/imports/ProfileBankFillerTest.java`

**Interfaces:**
- Produces: `DetectedAccountInfo` gains a last component `String bankSource` (`"FILE" | "PROFILE" | "NONE"`). A secondary constructor with the existing 28 parameters sets it to `"NONE"` when `bank == null || "OTHER".equals(bank.id())`, else `"FILE"`, so no existing call site changes. Add `DetectedAccountInfo withProfileBank(AccountDto.BankDto bank)`, which returns a copy with that `bank`, `suggestedName = bank.officialName()`, `bankSource = "PROFILE"`, and `productIdentityHash = null` (the full account number is not kept on the DTO, so the key cannot be rebuilt for the new bank; matching falls back to the masked number).
- Produces: `@Component ProfileBankFiller(JdbcTemplate jdbc, BankManagementService banks)` with
  - `record Filled<T>(T value, boolean bankConflict)`
  - `Filled<DetectedAccountInfo> fill(String fingerprint, DetectedAccountInfo detected)`
  - `Filled<StagingResponse> fill(String fingerprint, StagingResponse staged)`
  - `Filled<List<StagedAccountSection>> fill(String fingerprint, List<StagedAccountSection> sections)` (conflict = any section's)
  - `Optional<String> profileBankId(String fingerprint)`: `SELECT p.bank_id FROM layout_registry r JOIN layout_profile p ON p.id = r.profile_id WHERE r.fingerprint = ?`, empty for a null fingerprint, no row, or a null bank.

Rule per detected account: profile bank absent, or `resolve(profileBankId)` is OTHER → unchanged. Detected bank not OTHER → unchanged, and `bankConflict = !detected.bank().id().equals(profileBankId)`. Detected bank OTHER → `withProfileBank(resolve(profileBankId))`.

- [ ] **Step 1: Write the failing tests** (JdbcTemplate and BankManagementService mocked):
  - `fileBankIsKept_andNoConflictWhenProfileAgrees` (detected HDFC, profile HDFC → HDFC, FILE, false)
  - `fileBankIsKept_andConflictWhenProfileDisagrees` (detected ICICI, profile HDFC → ICICI, FILE, true)
  - `unknownBankIsFilledFromProfile` (OTHER + profile HDFC → HDFC, PROFILE, `suggestedName` "HDFC Bank", `productIdentityHash` null)
  - `noProfileLeavesItUnknown` (OTHER, empty → OTHER, NONE)
  - `profileBankThatNoLongerResolvesIsIgnored` (resolve returns OTHER → OTHER, NONE)
  - `nullFingerprintIsANoOp`
  - `eachSectionIsFilledOnItsOwn` (two sections: HDFC and OTHER, profile HDFC → both HDFC, sources FILE and PROFILE)
  - `legacyJsonWithoutBankSourceStillReads`: deserialize a `DetectedAccountInfo` JSON with no `bankSource` field using the app's `ObjectMapper`; assert it reads and `bankSource` is null. Then serialize a new one and assert the field is present.
- [ ] **Step 2: Run, see them fail.** `cd backend && ./mvnw -q test -Dtest=ProfileBankFillerTest`
- [ ] **Step 3: Implement** the record change and the filler as above.
- [ ] **Step 4: Run `ProfileBankFillerTest`, then `./mvnw -q test` for the whole unit suite (the record change touches 6 main and 11 test constructor sites).**
- [ ] **Step 5: Commit.** `feat(imports): fill an unknown bank from the layout's profile`

### Task 4: Run the filler on every staging path

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/ImportService.java` (CSV upload ~L235-257; PDF upload ~L362-417; `stageReparsed` ~L506-530; `reviewLayout` / `recordPdfParsed` signatures)
- Modify: `backend/src/main/java/com/finora/imports/LayoutReviewService.java:108` (`onStaged` overload with `boolean bankConflict`)
- Test: create `backend/src/test/java/com/finora/imports/ProfileBankFillIT.java`

**Interfaces:**
- Consumes: `ProfileBankFiller` (Task 3).
- Produces: `LayoutReviewService.onStaged(String fingerprint, String sourceFormat, List<StagedRow> rows, List<VerificationReport> reports, String analysisReference, LayoutIdentity identity, boolean holderUnreadable, boolean bankConflict)`; `bankConflict` adds `Reason.IDENTITY_CONFLICT`.

At each site: compute the fingerprint as today, keep the unfilled `staged` / `sections` for `DocumentIdentity`, `LayoutIdentity.of(...)` and `holderUnreadable(...)`, run the filler, and pass the filled value to `createSession` / `createMultiSection` and to the response. In `stageReparsed`, the fingerprint is `parsed.documentContext().buildFingerprint()` (via `fingerprintOf`), and the conflict is not reported there (it is not a review run). `parseAndStageAnyFormat` (refresh/reimport into an existing account) is left alone: the account already has its bank.

- [ ] **Step 1: Write the failing IT** `ProfileBankFillIT`, using a synthetic CSV with header `Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance`, two rows (one debit, one credit), no rows above the header, file name `statement.csv`:
  - `unknownBankIsFilledFromTheLayoutsProfile`: create profile "HDFC Bank — Savings / Current" with bank HDFC, register and link the fingerprint (`FP-1-CA196976`) to it; stage via `parseAndStageWithSession`; assert response `detectedAccount.bank.id` = "HDFC", `bankSource` = "PROFILE", `suggestedName` = "HDFC Bank"; the stored session's detected account says the same; the analysis row's `bank_name` is null (evidence unchanged).
  - `noProfileMeansNone`: same file, no link → bank OTHER, `bankSource` NONE.
  - `fileNamedBankWinsAndRaisesAConflict`: link to a profile with bank ICICI; stage the same file named `HDFC_statement.csv` → bank HDFC, `bankSource` FILE, and the layout's `review_reasons` contains `IDENTITY_CONFLICT`.
  - `filledBankDoesNotRegroupTheLayout`: after `unknownBankIsFilledFromTheLayoutsProfile`, the layout's `profile_link_source` and `profile_version` are unchanged.
  - `heldStatementReparseIsFilledToo`: `reparse` + `stageReparsed` for the same bytes with the profile linked; the stored session's bank is HDFC / PROFILE.
- [ ] **Step 2: Run, see it fail.** `-Dit.test=ProfileBankFillIT`
- [ ] **Step 3: Implement the wiring.** Inject `ProfileBankFiller` into `ImportService` the same way `layoutReviewService` is (nullable for unit tests that construct the service by hand; null means "no fill").
- [ ] **Step 4: Run `ProfileBankFillIT`, `LayoutReviewServiceTest`, `LayoutReviewAndProfilesIT`, and the import ITs that cover staging (`*Import*IT`, `*Staging*IT`); all pass.**
- [ ] **Step 5: Commit.** `feat(imports): apply the profile's bank when staging an upload`

### Task 5: At confirm, keep the identity key honest and record the choice

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/ImportService.java` (`confirmSession` single-section call ~L1077 and multi-section loop ~L1015-1040; `resolveTargetAccount` ~L2223)
- Create: `backend/src/main/java/com/finora/imports/ImportBankChoiceRecorder.java`
- Test: create `backend/src/test/java/com/finora/imports/ImportBankChoiceIT.java`

**Interfaces:**
- Produces: `@Component ImportBankChoiceRecorder(JdbcTemplate jdbc)` with `void record(String layoutFingerprint, DetectedAccountInfo detected, String chosenBankId)`. It inserts one row; `detected_bank_id` = `detected.bank().id()` (OTHER when null); `bank_source` = `detected.bankSource()`, or derived FILE/NONE when null (legacy session); `chosen_bank_id` = the created account's bank id.
- `resolveTargetAccount` change: when `na.bankId()` differs from the session's detected bank id, pass `null` instead of `na.productIdentityHash()` to `ProductIdentity.stored(...)`. The detected account is passed in by the caller; it is null on the session-less `confirm` overload, in which case nothing changes.

Recording happens after `persistSection` returns, only when the section's request had `newAccount != null` and `PersistedSection.accountsCreated()` is non-empty. The chosen bank id is read from the created account (`accountRepository.findById(persisted.accountId())`), not from the request.

- [ ] **Step 1: Write the failing IT.**
  - `newAccountFromAProfileFilledUploadRecordsProfileSource`: stage as in Task 4, confirm with `newAccount.bankId = "HDFC"` → one `import_bank_choice` row: fingerprint `FP-1-CA196976`, detected `HDFC`, source `PROFILE`, chosen `HDFC`.
  - `userPickedBankIsRecordedAgainstNone`: no profile; confirm with `bankId "HDFC"` → detected `OTHER`, source `NONE`, chosen `HDFC`.
  - `unknownChosenBankRecordsWhatTheAccountGot`: confirm with `bankId "NOT_A_BANK"` → chosen = the account's actual bank id (OTHER).
  - `existingAccountImportRecordsNothing`.
  - `changingTheBankDropsTheIdentityKey`: a synthetic CSV whose header row has a full account number above it, with bank HDFC detected; confirm with `bankId "ICICI"` and the staged `productIdentityHash`; the created account's `product_identity_hash` is null. The same confirm with `bankId "HDFC"` keeps the hash.
- [ ] **Step 2: Run, see it fail.** `-Dit.test=ImportBankChoiceIT`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run `ImportBankChoiceIT` plus the confirm ITs (`*Confirm*IT`, `MultiSection*IT`); pass.**
- [ ] **Step 5: Commit.** `feat(imports): record which bank each imported account got, without the user`

### Task 6: Admin counts — choices per layout, origins per week

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/LayoutCurationService.java`
- Modify: `backend/src/main/java/com/finora/controller/AdminLayoutRegistryController.java`
- Test: `backend/src/test/java/com/finora/imports/LayoutReviewAndProfilesIT.java`

**Interfaces:**
- Produces: `record BankChoiceCount(String fingerprint, String bankId, String bankName, long count)`; `List<BankChoiceCount> bankChoices()`: rows with `bank_source = 'NONE'` grouped by fingerprint and chosen bank. `GET /admin/imports/layout-registry/bank-choices`, READ permission.
- Produces: `record BankOriginWeek(LocalDate weekStart, long fromFile, long fromProfile, long chosenByUser, long otherBank)`; `List<BankOriginWeek> bankOrigins(int weeks)`, newest first, weeks clamped to 1..52, weeks start Monday (Asia/Kolkata). Classification per row: `chosen = 'OTHER'` → otherBank; else `source = 'NONE'` or `chosen <> detected` → chosenByUser; else `source = 'FILE'` → fromFile; else fromProfile. `GET /admin/imports/layout-registry/bank-origins?weeks=12`, READ permission.

- [ ] **Step 1: Write the failing tests.** Insert `import_bank_choice` rows directly:
  - `bankChoicesCountsOnlyUnknownBankUploads` (a NONE→HDFC ×2, NONE→OTHER ×1, PROFILE→HDFC ×1 on one fingerprint → HDFC 2, OTHER 1).
  - `bankOriginsClassifiesEachRow` (one row per class in one week → 1/1/1/1; a FILE row whose chosen differs → chosenByUser).
  - `bankOriginsWeeksAreClamped` (`weeks=0` → 1, `weeks=500` → 52).
  - both endpoints are 403 without READ permission.
- [ ] **Step 2: Run, see them fail.**
- [ ] **Step 3: Implement** with JdbcTemplate `GROUP BY` queries; bank names via `BankManagementService.resolve`.
- [ ] **Step 4: Run; pass.**
- [ ] **Step 5: Commit.** `feat(imports): admin counts of chosen banks per layout and bank origins per week`

### Task 7: Admin portal

**Files:**
- Modify: `admin-portal/src/types/index.ts` (`LayoutProfileView` + `bankId`, `bankName`; new `BankChoiceCount`, `BankOriginWeek`)
- Modify: `admin-portal/src/api/endpoints.ts:874-893` (`createProfile(name, bankId)`, `updateProfile(profileId, {name?, bankId?})` replacing `renameProfile`, `bankChoices()`, `bankOrigins(weeks)`)
- Modify: `admin-portal/src/pages/LayoutRegistryPanels.tsx` (`ProfilesPanel` create form and each profile's header; `ReviewQueuePanel` rows)
- Modify: `admin-portal/src/pages/LayoutIntelligence.tsx` (a "Where new accounts got their bank" panel)
- Test: `admin-portal/src/pages/LayoutIntelligence.test.tsx`

**Interfaces:**
- Consumes: the endpoints from Tasks 2 and 6; `banksApi.search()` (already used by `pages/user-detail/AccountsSection.tsx:98`, query key `['banks-picker']`).

UI, using the page's existing classes:
- Create profile: a bank `<select>` (required, `OTHER` excluded) next to the name; "Create profile" disabled until both are set.
- Each profile header: its bank name, or "No bank set" with the same select to set one.
- Review queue row: when `bankChoices` has entries for the fingerprint, a line "Users chose: HDFC Bank 2 · Other bank 1". Nothing when there are none.
- Bank-origins panel: a small table, one row per week: From the file / From a profile / Chosen by the user / Other bank.

- [ ] **Step 1: Write the failing tests** (mock the API as the file already does): create button disabled until a bank is chosen and `createProfile` is called with `(name, bankId)`; "No bank set" shown and choosing a bank calls `updateProfile(id, { bankId })`; review-queue line text exactly `Users chose: HDFC Bank 2 · Other bank 1`; the weekly table renders four counts for a week.
- [ ] **Step 2: Run, see them fail.** `cd admin-portal && npx vitest run src/pages/LayoutIntelligence.test.tsx`
- [ ] **Step 3: Implement.** Regenerate `admin-portal/src/api/generated-types.ts` with the repo's existing script if one exists (check `package.json`), else leave it untouched and say so in the PR.
- [ ] **Step 4: Run the tests, then `npm run lint` and `npx tsc --noEmit` in `admin-portal`; all clean.**
- [ ] **Step 5: Commit.** `feat(admin-portal): profile banks, users' bank choices, and bank origins per week`

### Task 8: Verify end to end, then open the PR

- [ ] **Step 1: Corpus, before and after.** On `origin/main` and on this branch, run the corpus ground truth (`python3 scripts/run-corpus-ground-truth.py "/Users/sid/Downloads/Bank statement"`) and a per-file dump of detected bank, account type, and row hash (date|amount|type|description). Expected: identical; the corpus never touches a profile.
- [ ] **Step 2: Full backend run.** `cd backend && ./mvnw verify` (unit + IT). Expected: 0 failures. Do not commit while it runs.
- [ ] **Step 3: Admin portal full run.** `npx vitest run`, `npm run lint`, `npx tsc --noEmit`.
- [ ] **Step 4: Re-read the spec's sections 1, 2, 5, 6, 8.** Confirm each has a passing test behind it; list anything deferred, with why.
- [ ] **Step 5: Merge-state check, push, PR.** Per repo `CLAUDE.md` (`git log origin/main..HEAD`, check `gh repo view --json visibility`; if PRIVATE, add `full-ci` and push once when ready). PR body says: after deploy, an admin sets banks on hand-made profiles, starting with "ICICI Bank — Credit Card".
