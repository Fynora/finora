# Gate 2a: learn why people don't get a statement in

Date: 2026-10-16. Status: **approved by Sid, 16 Oct, including all copy.**

Part of the activation plan v3 (approved 2026-10-09), Gate 2. Gate 2 is now two specs:

- **2a (this one): learn why.** Find out, for every user and continuously, where they stop on the way
  to their first statement and what they say stopped them.
- **2b: remove barriers.** `2026-10-16-gate2b-remove-barriers-design.md`. Make the statement easier
  to get, say plainly what happens to it, and bring people back each month.

This replaces the single Gate 2 draft of the same date. That draft learned from about ten testers in
one round of sessions. Sid's direction (16 Oct): solve for real users, not only testers. So 2a asks
every user, at the moment they stop, and keeps a live picture an admin can read without SQL. The
observed sessions stay, as the deep look behind the numbers.

> **Source note.** The plan v3 document lived in a session scratchpad that has since been cleared.
> The observed-session design in §7 is reconstructed from notes taken when it was read on 10 Oct.

## 1. Evidence

Production, read-only, 15 Oct 2026. Of 26 real users (Sid's own and test accounts taken off):

| Group | Users |
|---|---:|
| Got data in | 6 |
| Tried, got nothing in | 4 |
| Never uploaded | 16 |

- **Not established:** why the 16 never uploaded. Nothing recorded says whether they never opened the
  import screen, opened it and chose no file, or chose a file and gave up.
- Two of the four who got nothing in were stopped by our own process (a trust rejection, and an
  approved statement that expired unconfirmed), not by the first screens.

Code, 16 Oct (`main` at `d2add8fe1`):

- **Onboarding already asks two questions.**
  - "What would you like to achieve with Fynora?" (`FinancialFocusScreen`, stored in
    `user_financial_focus`, V162). It can be skipped along with the rest of the welcome flow
    (`skipEverything`).
  - The required "How do you keep track of your spending today?" (`SpendingTrackingQuestion`, stored
    on `users.spending_tracking_method`, V256). Admin Platform Analytics already shows its answers.
  - Both are in the data export and removed by the account purge.
  - The focus screen says "We'll personalize your experience". Nothing reads the answers except the
    export and the purge, so that promise is not true today.
- **Account deletion asks no reason.** `UserAccountLifecycleService.requestDeletion` marks the user
  for purge and records an audit entry. The purge then removes or anonymises everything linked to
  the user, so a reason stored against the user would be deleted with them.
- **Anonymous web navigation counts** (`trackNavigation`, `POST /nav-events`) carry no user id. They
  cannot split users into groups.
- **`user_activity_days`** (V249) records one row per user per IST day. It has a privacy line, an
  export and a purge, and is the pattern for the per-user records below.
- **The web review's "Cancel Import"** discards the staged session through a confirmation dialog
  (`Import.tsx`, `discardReviewSessionAndStartOver`).
- **Admin Platform Analytics** (`PLATFORM_ANALYTICS_VIEW`) is the natural home for the funnel page.
- **No flag marks an account as internal or test.** USER-scope accounts are all counted alike.

## 2. Scope

**In 2a:**

1. The import-funnel record: three moments per user per day (§3).
2. "What stopped you?": one-tap questions at the moments people stop (§4).
3. A reason when someone deletes their account, kept with no link to them (§5).
4. The activation page in the admin portal (§6), which also shows the onboarding answers.
5. Observed sessions with real users, and the kit to run them (§7).

**Not in 2a:**

- Anything that changes what users see on the import path, apart from the questions themselves. That
  is 2b.
- Making onboarding actually personalise anything. 2a corrects the copy instead (§4.5).
- Reasons to come back after the first statement (Gate 4).

**Anti-goals** (a failure even if it technically works):

- **No free text in any question.** A typed answer can carry amounts, names or account numbers.
- **Never recorded:** a time of day, a file name, size or type, or anything from inside a statement.
- **Never nagging.** A question that interrupts a user who is trying to do something is worse than no
  answer (§4.3 sets the limits).
- **No sample data** in anyone's account (Sid, 9 Oct).
- **No percentage targets.** At this size one person moves any rate by about 4 points. The page shows
  counts.
- **No names, emails or user ids on the admin page.**

## 3. The import-funnel record

Unchanged from the earlier draft.

For each user, on each IST calendar day, at most once each:

| Event | Fired when |
|---|---|
| `IMPORT_SCREEN_OPENED` | Web: `/app/import` is shown. Mobile: the Import tab gains focus. |
| `FILE_CHOSEN` | The user picks a file, before any upload starts. Also fired for a file the app then refuses itself (wrong type, too big). |
| `REVIEW_OPENED` | The review of staged rows is shown, from any path: right after upload, resumed from "Continue previous import", or opened from a completed import. |

**Server:**
- Table `import_funnel_days` (user, event, date), unique on all three so repeats collapse.
- The date is the day in `app.platform.reporting-zone` (Asia/Kolkata), as for `user_activity_days`.
- One signed-in endpoint takes an event name from the fixed list and nothing else. An unknown name is
  refused. The user is always the caller, never a parameter.

**Clients:**
- Fire and forget. A failed call is never retried, never shown, and never delays the screen.
- Each event is sent at most once per screen visit.

**Privacy:**
- Export includes the rows; the account purge deletes them explicitly, proven in
  `AccountPurgeSweepServiceIT`.
- The privacy line is in §8.

With what is already recorded, each user's furthest step reads as one of:
1. signed up;
2. opened import;
3. chose a file;
4. refused before reading (`statement_analysis_sessions` REJECTED);
5. read failed (FAILED);
6. held for review;
7. read, never reviewed;
8. reviewed, never confirmed;
9. got data in.

## 4. "What stopped you?"

### 4.1 Where it is asked

| Moment | When and where it shows | Question | Answers (one tap, or skip) |
|---|---|---|---|
| `NO_FILE` | On the dashboard, the next day or later, for a user who opened the import screen, has never chosen a file and has no data in. A small card, not a dialog. | "Last time you looked at adding a statement. What stopped you?" | "I don't have my statement" · "I'm not comfortable uploading it" · "I didn't know how" · "I didn't see the point yet" · "Not now" |
| `REFUSED` | On the import screen, inside the panel that shows a refusal or a failed read, below the reason and its next action. | "What will you do now?" | "Try another file" · "I don't have another statement" · "I don't understand why it failed" · "Leave it for now" |
| `NOT_CONFIRMED` | (a) Web and mobile: right after the user confirms "Cancel Import" on a review. (b) On the dashboard, for a user whose statement was read and then expired unconfirmed (Gate 1 records it: `expired_unconfirmed_at` for a direct upload, review state EXPIRED for a queued import). | "What made you stop?" | "The rows looked wrong" · "I uploaded the wrong file" · "I'm not ready to save it" · "I'll come back to it" |

Every question has a visible "Skip". *(Corrected 17 Oct: a skip is recorded, only so §4.3 can
count it -- see §4.4. This line used to say it records nothing.)*

The answer lists are my draft for Sid to approve. Each list is fixed in the server and the clients
share it. A new answer needs a server change, never a client-only one.

### 4.2 Who decides whether to show it

- **The server decides the dashboard cards.** A signed-in endpoint returns the one question this
  user should see now, or none. It works this out from the funnel record, the import records and
  §4.3's limits.
- **The clients decide the import-screen questions,** since they know the moment, but the server
  refuses an answer that §4.3 does not allow. *(Added 17 Oct:)* so before showing one, the client
  asks the same endpoint whether that moment may be asked now, and shows nothing if not. Otherwise
  a moment inside its 30 quiet days would still be offered, and the tap would be refused silently.

### 4.3 Limits

"Asked" means answered or skipped: the apps cannot tell whether someone read a card they did not
touch. *(Clarified while planning, 16 Oct.)*

- **Per moment:** once a user answers or skips a moment, it is not asked again for 30 days. A
  question left untouched may show again the next time its moment happens; a dashboard card stays
  until it is answered, skipped (its close button counts as Skip) or no longer applies.
- **Per user:** at most one answer or skip per user per day, of any moment. After one, no other
  question shows that day.
- **The `NOT_CONFIRMED` dashboard card** is about a read that expired in the last 7 days, so an old
  expiry never brings it back.
- **The `NO_FILE` card** is never shown to a user who has data in. The `NOT_CONFIRMED` card can be,
  since a user with data can still lose a later statement to expiry.
- **Never during an action:** a question never appears while an upload, read or confirm is in
  progress, and never blocks the next step.

### 4.4 What is stored

- Table `activation_answers`, holding:
  - user;
  - moment;
  - answer, or `SKIPPED`;
  - the date in the reporting zone.
- A skip is stored only so §4.3 can count it. The admin page shows skips as a count.
- Export and purge as in §3. The privacy line is in §8.

### 4.5 The onboarding copy

The focus screen's "We'll personalize your experience" is changed in both apps to "It helps us
understand what you want from Fynora." *(My call: correcting the copy is cheaper and more honest than
building personalisation nobody has designed.)*

## 5. A reason when someone deletes their account

- **Where:** in the delete flow (web `DeleteAccountModal`, mobile `DeleteAccountSheet`), one optional
  question.
  - Question: "Before you go: what's the main reason?"
  - Answers:
    - "I couldn't get my statements in"
    - "I'm not comfortable with my data here"
    - "It didn't show me anything useful"
    - "I use another app"
    - "I don't need it anymore"
    - "Something else"
  - Skipping is always allowed and changes nothing about the deletion.
- **Sent with the deletion request**, as an optional field on `DeleteAccountRequest`. It is written in
  the same transaction that marks the user for purge, so a reason exists only for a deletion that
  happened.
- **Stored with no link to the user:**
  - Table `account_deletion_reasons`, holding the reason, the **month** of deletion, and whether the
    user had any data in.
  - No user id, no email, no date finer than the month.
  - The month, not the day, keeps a row from being matched to the deletion's audit entry by
    timestamp at today's small numbers.
- **Kept after purge.** It is not personal data once unlinked. The privacy line in §8 says so.

## 6. The activation page (admin portal)

A new page under Analytics, gated by `PLATFORM_ANALYTICS_VIEW`. Counts only.

1. **Funnel by signup week.**
   - Rows: signup weeks (IST).
   - Columns: the nine furthest steps from §3.
   - Each cell is a count of users. A cell links nowhere.
2. **"What stopped you?" answers.** For each moment, the count of each answer and of skips, over a
   chosen date range.
3. **Answers next to steps.** For each furthest step, how those users answered, and their onboarding
   focus and spending-tracking answers (including "no answer").
4. **Deletion reasons.** A count per reason per month.

**Rules:**
- Excludes ADMIN-scope accounts.
- Excludes USER-scope accounts an admin has marked "internal":
  - a new column `users.internal_account`, set from the admin user page, audited;
  - *(my call: no such flag exists, and without it Sid's own and test accounts are counted with real
    users).*
- Funnel steps that depend on the funnel record count only from the day it went live. The page says
  that date.
- The page states that the counts are small and a single person changes them.

## 7. Observed sessions

Up to 10 real users, at least 5, recruited from the user list with their consent:
- 4 who never uploaded;
- 3 who tried and failed;
- 3 who got data in.

Sid runs the sessions or is present.

Each session has three parts:

1. **Unassisted.** "Use Fynora as you normally would." Watch and explain nothing.
2. **The task.** "Find out where your money went last month." Record each problem **before**
   helping, then help to find the next one. The unaided and the assisted outcome are recorded
   separately.
3. **Five questions:**
   1. Walk me through the last time you opened Fynora.
   2. When the app asked for a statement, what did you do next?
   3. What went through your mind about uploading a statement here?
   4. The last time you checked your money elsewhere, what were you trying to find out?
   5. Since then, was there a moment you thought of opening Fynora?

**Privacy:**
- Consent is asked before the session and again before any recording.
- Notes record what the person did and said, never amounts, names, account numbers or merchants.
- No screenshots of statement content.
- A recording is deleted once notes are written, and never shared.

**The kit** (documents only) lives in `docs/research/gate2/`:
- a session guide;
- a notes template (what they did and what they said in separate columns, both outcomes, the
  barriers in order);
- a recruitment message per group;
- consent wording.

It holds no one's personal data. Filled notes never go in the repository.

**Output:** a ranked list of barriers, each with what was seen, what was said, how often the §4
answers name it, and a confidence (High, Medium, Low).

## 8. Privacy page lines (approved 16 Oct)

Added to the paragraph that already covers activity dates:

> We also record the dates on which you opened the import screen, chose a file to upload and opened
> an import's review, and any answer you tap when Fynora asks what stopped you. We record the date
> only, never the time, the file or anything in it, so we can see where adding a statement gets
> difficult. These records are linked to your account and are deleted if you delete your account.

> If you tell us why you are deleting your account, we keep that answer with the month only and no
> link to you or your account, so it is not deleted with your account.

Nothing in §3 to §5 ships to production before these lines are live.

## 9. Delivery

Small pieces, each its own PR:

1. **Session kit** (documents). Lets recruiting start at once.
2. **Funnel record:** server, endpoint, export, purge, privacy lines.
3. **Funnel events in web and mobile.** Mobile goes by OTA, never to 1.0.0 builds.
4. **"What stopped you?"**: server (answers table, the next-question endpoint, limits), then web and
   mobile, plus the onboarding copy fix.
5. **Deletion reason:** request field, table, both apps.
6. **Activation page and the internal-account flag.**

## 10. Exit criteria for 2a

1. The funnel record, the questions and the deletion reason are live in production, on web and on
   mobile through OTA. The privacy lines are published.
2. The activation page shows real rows, checked against a read-only query Sid runs.
3. At least 5 observed sessions are held across the three groups, each with a filled notes template.
4. The ranked barrier list is written from sessions and answers together.
5. Every change is verified per CLAUDE.md: tests, lint, typecheck, full backend `verify`, CI.

**Decision at the gate:** which 2b items to do first, and whether the bigger bets that were set aside
(email forwarding, bank guides, Account Aggregator) are now justified by the answers.

## 11. Calls made in this spec (approved 16 Oct)

- The answer lists in §4.1 and §5.
- The limits in §4.3: 14 days per moment, one question a day, 30 days after an answer.
  *(Note 17 Oct: once "asked" was clarified to mean answered or skipped, §4.3 has one per-moment
  limit, 30 days. No separate 14-day limit is built.)*
- No free text anywhere.
- The dashboard card for `NO_FILE` asks the next day, not the moment the user leaves the import
  screen.
- The deletion reason keeps the month only and survives the purge.
- The new `users.internal_account` flag.
- The onboarding copy change in §4.5.
