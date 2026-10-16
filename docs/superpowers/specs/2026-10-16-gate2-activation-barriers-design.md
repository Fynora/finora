# Gate 2: we know why testers don't activate

Date: 2026-10-16. Status: **draft for Sid's approval.**
Part of the activation plan v3 (approved 2026-10-09): Gate 2 of four. Gate 2 asks one question:
**why do testers not get a statement in, and what stops them?**

> **Source note.** The plan v3 document lived in a session scratchpad that has since been cleared.
> Sections marked *(from plan v3)* are reconstructed from notes taken when it was read on 10 Oct,
> not copied from it. Check them against what you approved. Everything else is new to this spec.

## 1. Evidence this spec is built on

Production, read-only, 15 Oct 2026 (32 user-scope accounts; 26 real users once Sid's own and the
test accounts are taken off):

| Group | Users |
|---|---:|
| Got data in | 6 |
| Tried, got nothing in | 4 |
| Never uploaded | 16 |

- 26 of 32 accounts had not opened Fynora on 14 or 15 Oct. 13 have no visit at all since activity
  tracking started on 3 Oct.
- Two users who joined on 11 and 14 Oct uploaded and had no statement read. The records Gate 1
  added (refused uploads, reads that expired unconfirmed) went live on 10 Oct, so they may say why;
  they have not been read for these two yet.
- Two of the four who got nothing in were stopped by our own process (a trust rejection, an
  approved statement that expired unconfirmed), not by the product's first screens.
- **Not established:** why the 16 never uploaded. Nothing recorded can say whether they never
  opened the import screen, opened it and chose no file, or chose a file and gave up. That is the
  gap this gate closes.

Code, 16 Oct (`main` at `d2add8fe1`, which includes #2228):
- Web already sends anonymous navigation counts (`trackNavigation`, `POST /nav-events`): which
  destination was opened, with no user, session or device id. It can count visits to the import
  screen in aggregate, but it cannot say which users did, so it cannot split a cohort.
- `user_activity_days` (V249) records one row per user per IST day with any signed-in request. Its
  privacy line already exists (Privacy page, "We also record the dates on which you use Fynora"),
  and the data export and account purge cover it. It is the pattern the new record follows.

## 2. Scope

**In Gate 2:**
1. Observed sessions with testers, and the ranked list of barriers they produce *(from plan v3)*.
2. A per-user record of three import-funnel moments, with its privacy line (Sid's decision of
   9 Oct moved these here from Gate 1).
3. Phase 1a, reliability and recovery: an audit of the six items against the code, then building
   only what is missing *(items from plan v3)*.

**Not in Gate 2:**
- Phase 1b, the guided first import *(from plan v3)*. Designed only after the barrier list, as its
  own spec, because the sessions decide what it needs.
- Anything about the first result's accuracy or usefulness (Gate 3) or reasons to come back
  (Gate 4).
- A direct upload left unconfirmed vanishing at expiry, and the mobile "ready" push opening a screen
  that does not list the waiting review. Both are known (PR #2228's description) and belong to Gate 1
  follow-ups, not here.

**Anti-goals** (a failure even if it technically works):
- Sample or demo data in anyone's account (Sid's decision, 9 Oct).
- Analytics standing in for observed understanding. The events say where people stop, never why.
- Percentage targets. With 26 users, one person moves any rate by about 4 points.
- Recording what anyone typed, which file they chose, its name, or anything in it.
- A per-request log or a time of day. Dates only, as `user_activity_days` promises.

## 3. Observed sessions *(from plan v3)*

### 3.1 Who and how many
Up to 10 testers, at least 5:
- 4 who never uploaded;
- 3 who tried and failed;
- 3 who got data in.

Recruited from the 15 Oct user list. Sid runs the sessions or is present at them.

### 3.2 Each session
1. **Unassisted.** "Use Fynora as you normally would." Watch what they look for and whether they
   start an import. Explain nothing.
2. **The task.** "Find out where your money went last month." Watch whether they find a
   statement, understand the upload and complete it. Record each problem **before** offering help;
   then help, to find the next barrier. Record the unaided and the assisted outcome separately.
3. **Five questions:**
   1. Walk me through the last time you opened Fynora.
   2. When the app asked for a statement, what did you do next?
   3. What went through your mind about uploading a statement here?
   4. The last time you checked your money elsewhere, what were you trying to find out?
   5. Since then, was there a moment you thought of opening Fynora?

### 3.3 Privacy in sessions
Testers use their own statements, so sessions will show real financial data.
- Ask consent before the session, and again before any recording.
- Notes record what the person did and said, never amounts, names, account numbers or merchants.
- No screenshots of statement content. If a session is recorded, the recording is deleted once the
  notes are written, and is never shared.

### 3.4 What the builder prepares (the session kit)
- **Session guide:** the steps above, word for word, with when to help and when not to.
- **Notes template:** one per session, with what they did and what they said kept in separate
  columns, the unaided outcome, the assisted outcome, and each barrier hit, in order.
- **Recruitment message:** short, plain, what it involves (about 30 minutes, their own statement,
  nothing recorded without asking).
- **Consent wording** for the start of the session and before any recording.

The kit lives in `docs/research/gate2/` (my call), and holds no tester's personal data. Filled-in
notes do not go in the repository.

### 3.5 Output: ranked barriers
| Barrier | What we saw | What they said | Proposed response | Confidence |
|---|---|---|---|---|

What people do and what they say are recorded separately: "I didn't know how" can hide "it wasn't
worth it". The output is a ranked list of barriers, not a list of feature requests. Confidence is
High, Medium or Low, and says how many sessions showed it.

## 4. The import-funnel record

### 4.1 What is recorded
For each user, on each IST calendar day, at most once each:

| Event | Fired when |
|---|---|
| `IMPORT_SCREEN_OPENED` | The import screen is shown: web `/app/import`; mobile Import tab gaining focus. |
| `FILE_CHOSEN` | The user picks a file, before any upload starts. Fired for a file the app then refuses itself (wrong type, too big). |
| `REVIEW_OPENED` | The review of staged rows is shown, from any path: right after upload, resumed from "Continue previous import", or opened from a completed import. |

Stored as user, event name and date. No time, no file name, size or type, no screen detail, no
device. This is the minimal record the Gate 1 spec (§5.3) defined.

Together with what is already recorded, a user's first attempt then reads as one of:
never opened the import screen; opened it and chose no file; chose a file and was refused
(`statement_analysis_sessions` REJECTED); uploaded and the read failed (FAILED); read and never
reviewed; reviewed and never confirmed; got data in.

### 4.2 How it is recorded
- **Server:** a new table (my call on the name: `import_funnel_days`), with a unique key on user,
  event and date so repeats collapse to one row, as `user_activity_days` does. The date is the
  calendar day in the platform reporting zone (`app.platform.reporting-zone`, Asia/Kolkata), the
  same zone `user_activity_days` uses. Next free migration number confirmed at build time.
- **Endpoint:** one signed-in endpoint that takes an event name from the fixed list of three and
  nothing else. An unknown name is refused. The user is the caller, never a parameter.
- **Clients:** fire and forget. A failed call is never retried, never shown, and never delays the
  screen, the same rule `trackNavigation` follows. Each client sends each event at most once per
  screen visit, and the server collapses the rest.
- **Mobile** reaches users through an OTA update. Per the glass UI notes, Phase 2 JavaScript must
  not go to 1.0.0 builds; the builder confirms the target runtime first.

### 4.3 Privacy, export and deletion
- **Privacy line**, added to the paragraph that already covers activity dates (wording for Sid to
  approve before release):
  > We also record the dates on which you opened the import screen, chose a file to upload, and
  > opened an import's review — the date only, never the time, the file or anything in it — so we
  > can see where importing a statement gets difficult. These records are linked to your account
  > and are deleted if you delete your account.
- **Data export** includes the user's rows, as it includes `user_activity_days`.
- **Account deletion** removes them: `AccountPurgeSweepService` deletes them explicitly, as it does
  `user_activity_days`, and `AccountPurgeSweepServiceIT` proves it.
- Nothing ships to production before the privacy line is live.

### 4.4 Reading it
A read-only query, run by Sid, that prints each user's funnel stage (first attempt and latest) next
to the existing records. It joins on user id prefix only and prints no names, emails or file names.

## 5. Phase 1a: reliability and recovery

The six items are from plan v3. Their status below is from the code on 16 Oct. The first task is to
confirm each by driving the app, not only by reading code.

| # | Item | Status | Evidence |
|---|---|---|---|
| 1 | Password help that says it is not a UPI PIN, card PIN or netbanking password | **Missing** | Both apps' password hint names the usual ingredients (name, PAN, date of birth, account number) and says to check the email. Neither says what it is *not*. |
| 2 | A wrong password gives a specific error and a retry without picking the file again | **Present** | Web keeps the chosen PDF for the retry (`Import.tsx`, `pendingPdf`); mobile does the same (`ImportScreen.tsx`, `pendingPdf`). Both say "That password didn't open this statement". |
| 3 | An unsupported file gives a reason and one next action | **Partly** | Ten import failure codes have a title and a message in both apps (`importFailureMessages.ts`). Refusals before reading (wrong type, too large, scanner, server busy) and the remaining import codes are not in that map; whether each still gets a reason and an action elsewhere is **not established**. |
| 4 | A parser failure has a defined retry path | **Present** | Held for review, then reprocessed; or failed with a reason, retried by a new upload (Gate 1, #2100). |
| 5 | Re-running never duplicates | **Present** | Gate 1, `ImportRetrySafetyIT` (#2100). |
| 6 | A held outcome is visible without the push | **Present** | #2056, #2078, #2228. |

Also found while checking item 3: **mobile refuses a statement covering more than one account**
("can only be imported from the Fynora web app for now"). That is a "web matching mobile" gap. Fixing
it is larger than Phase 1a; it is recorded here, and the barrier list decides whether it matters.

**Build:** item 1, and whatever item 3's audit finds missing. Each refusal and failure a user can hit
gets one reason and one next action in both apps, in the same words.

**Item 1 copy** (my draft, for Sid to approve): "This is the password your bank set for the statement
file, usually a mix of your name, PAN, date of birth or account number — not your UPI PIN, card PIN or
netbanking password. Check the email the statement came in."

## 6. Delivery

Small pieces, in this order, each with its own PR:
1. **Session kit** (documents only), so recruiting can start at once.
2. **Funnel record:** server, endpoint, export, purge, privacy line; then web and mobile events.
3. **Phase 1a:** item 3's audit, then the password copy and whatever the audit finds.

The sessions themselves run alongside 2 and 3 and need Sid.

## 7. Exit criteria for Gate 2

1. At least 5 sessions held, across the three groups, each with a filled notes template.
2. The ranked barrier list written, with evidence and a confidence for each barrier.
3. The funnel record live in production on web, and on mobile through an OTA update, with the
   privacy line published. Proven by a query showing real rows.
4. Every Phase 1a item done, or deliberately deferred with the reason written down.
5. Every change verified per CLAUDE.md: tests, lint, typecheck, full backend `verify`, CI.

**Decision at the gate:** design Phase 1b from the barrier list, or revise.

## 8. Calls made in this spec (for Sid to check)
- The kit lives in `docs/research/gate2/`; filled-in notes stay out of the repository.
- The table is named `import_funnel_days`; events are the three above and no others.
- `FILE_CHOSEN` fires for files the app itself then refuses, so "chose a file" and "the server
  refused it" stay distinguishable.
- The draft password copy in §5.
- Recordings are deleted once notes are written.
