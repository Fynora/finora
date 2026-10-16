# Gate 2b: remove the barriers to a first statement

Date: 2026-10-16. Status: **draft for Sid's approval.**

Part of the activation plan v3, Gate 2. Its partner, `2026-10-16-gate2a-learn-why-design.md`, finds
out why people stop. This spec removes the barriers we can already see, for every user:

- the statement is hard to get to the app;
- the ask feels big;
- it is unclear what happens to the file;
- nothing brings people back when the next statement arrives.

The items are the ones Sid chose on 16 Oct from a longer list:

| Ref | Chosen item | Here |
|---|---|---|
| 7 | Share to Fynora from the mail app | §3 |
| 8 | A card statement as another way in | §4 |
| 9 | Ask for less | §4 |
| 10 | Upload from the laptop | §7 |
| 11 | What happens to the file | §5 |
| 12 | "You check every row before anything is saved" | §5 |
| 15 | A month-start nudge | §6 |

Phase 1a of the earlier Gate 2 draft (password help, a reason and a next action for every refusal)
also removes barriers, so it moves here (§8).

Set aside by Sid:
- bank guides with password formats;
- forwarding the statement email;
- a picture of the result;
- follow-ups by stage;
- personal messages;
- Account Aggregator.

## 1. Evidence

Code, 16 Oct (`main` at `d2add8fe1`):

- **Sharing a file into Fynora:**
  - **Android** accepts a single shared PDF or CSV (`expo-share-intent`, `androidIntentFilters` in
    `mobile/app.config.ts`).
  - **iOS** has a Share Extension rule for one PDF or CSV (`iosActivationRules`). The config comment
    asks for it to be checked against a real Simulator share; whether that check was done is **not
    established**.
  - Nothing in either app tells users they can share a statement in.
- **Credit card statements are read** (`CreditCardStatementTotalsValidator`,
  `CreditCardFlowReconciliationValidator`, and card accounts in `Setup.tsx`). Which issuers read
  cleanly is **not established** for this spec. §4 says how the builder checks before any copy names
  card statements.
- **What happens to an uploaded file today:**
  - The Privacy page (§"Uploaded Statements") says the original is stored and linked to the
    account, so it can be re-downloaded or re-read. It says statements are read by Fynora's own
    rules and not sent to third-party AI services.
  - It says that when an import cannot be processed automatically, authorised staff may open it,
    and every opening is logged.
  - In code, staff open an original only through `OriginalStatementViewService`: an Admin types a
    reason, the opening is audited, and a notice to the customer is written in the same transaction.
  - Originals live in Cloudflare R2. `StatementStorageSweepService` deletes a file only once nothing
    references it **and** 90 days have passed (`retention-days: 90`, Sid's decision, "statements stay
    re-importable for ~90 days").
  - **The Privacy page does not mention the 90 days.** It says nothing about how long a deleted
    statement's file stays in storage.
  - Whether R2 is configured in production (so that files are stored at all) is **not established**
    from code.
- **Nothing is saved before the user confirms.** Rows are staged in an import session and written
  only on confirm (`claimForConfirmation`). A held statement, once approved, still waits for the user
  to confirm (Gate 1). No screen says this before upload.
- **Notifications:**
  - The outbox has types per event (`NotificationType`) and preferences per category and channel
    (`NotificationCategory`: SECURITY, FINANCIAL, MARKETING).
  - Today the settings screens offer one switch per channel for FINANCIAL. MARKETING is opt-in and
    has no send logic.
  - On mobile, `usePushNotificationNavigation` maps a type to a screen; an unknown type opens the
    app without routing.
- **Phase 1a audit** (from the earlier draft, 16 Oct):
  - **Password hint:** both apps' hint names the usual ingredients and says to check the email, but
    neither says what the password is **not**.
  - **Refusals:** ten import failure codes have a title and message in both apps
    (`importFailureMessages.ts`). Refusals before reading (wrong type, too large, scanned, server
    busy) and the remaining codes are not in that map. Whether each gets a reason and a next action
    elsewhere is **not established**.
  - **Multi-account statements:** mobile refuses a statement covering more than one account
    ("can only be imported from the Fynora web app for now").

## 2. Scope and anti-goals

**In 2b:** §3 to §8.

**Not in 2b:**
- The guided first import (Phase 1b), which waits for 2a's barrier list.
- Importing multi-account statements on mobile. It is recorded in §8, and 2a's answers decide whether
  it matters.

**Anti-goals:**
- **Copy that says more than the code does.** Every sentence about what happens to a file must be
  true in production on the day it ships, and match the Privacy page word for word in meaning.
- **A computer that can read the account.** The laptop upload in §7 can add a file and nothing else.
- **Nudges that nag.** Hard limits in §6.
- **Naming a bank or an amount on a lock screen.**
- **Sample data** in anyone's account.

## 3. Share to Fynora (item 7)

1. **Verify sharing end to end on both platforms.**
   - Share a statement PDF from the mail app and from Files into Fynora.
   - Check it lands on the import screen with the file chosen, on Android and on iOS (Simulator and a
     real phone).
   - If iOS does not work, fixing it is part of this item.
2. **Tell people.**
   - On the mobile import screen, under the file picker: "Statement in your email? Open the email,
     tap the PDF, then Share → Fynora."
   - The same line goes in the import help text.
   - Web has no equivalent.
3. **Funnel:** a shared file counts as `FILE_CHOSEN` (2a §3).

## 4. Ask for less, and offer a card statement (items 8 and 9)

On the import screen in both apps, above the picker, while nothing is being imported:

> **Start with one statement:** last month, from the account you use most.
> No bank statement to hand? A credit card statement works too.

The dashboard checklist's import item uses the same first line.

- **The card line ships only after the builder checks the real statement corpus.** It needs a
  value-level read, per CLAUDE.md, of how many card statements per issuer import with correct rows.
  If coverage is narrow, the line names only the issuers that read cleanly, or is held back.
  Counts per issuer go in the PR description, never statement values.
- Copy is my draft for Sid to approve.

## 5. What happens to my statement (items 11 and 12)

### 5.1 First, settle the 90 days

The panel must state how long a deleted statement's file stays in storage. Today the Privacy page
says nothing about it. Before §5.2 ships:

- **Sid decides:**
  - (a) the Privacy page gains a sentence saying a deleted statement's file is removed from storage
    within 90 days; or
  - (b) a deleted statement's file is removed sooner, which is a code change to the sweep.
- *My recommendation: (a).* The 90 days was Sid's product decision, and only the text is missing.
- **Confirm with Sid** that R2 is configured in production.

### 5.2 The panel

A collapsible "What happens to my statement?" next to the file picker on the import screen, in both
apps. Closed by default, one tap to open. My draft, every line traced to §1:

- **Fynora reads it with its own rules.** It is not sent to AI services.
- **The original file is kept in your account,** so you can download it or have it read again later.
- **You check every row before anything is saved.** If you cancel, nothing is added.
- **If it can't be read automatically,** our team may open it to fix the problem. Every opening needs
  a written reason, is logged, and you are told.
- **You can delete a statement any time** from Statement History. *(Then the line settled in §5.1.)*
- **A PDF password is saved only if you choose,** and is stored encrypted.
- A link to the Privacy page's "Uploaded Statements" section.

The builder checks each line against the code before merging. For "you are told", that means both
held paths (held imports and held statements) write the customer notice.

### 5.3 The promise at review

- On the review screen in both apps, above the rows: "Nothing is saved until you confirm."
- On a held statement that has been approved: "Approved. Nothing is saved until you confirm."

## 6. The month-start nudge (item 15)

**Two cases:**

| Case | When | Message (push and in-app) |
|---|---|---|
| No data in yet | The 3rd of each month, 10:00 IST | "Your [Month] statement is out. It's a good one to start with, and takes a couple of minutes." |
| Has data in | The first day a user has an account whose latest imported statement period ended more than 33 days ago with nothing newer | "A new statement has probably arrived for one of your accounts. Add it to keep your months complete." |

**Rules:**
- **At most one nudge per user per calendar month,** across both cases and all accounts.
- **The no-data case stops after two months in a row with no import.** It starts again only if the
  user opens the import screen.
- **No bank name, account or amount in the text** *(my call: it shows on lock screens)*.
- **Tapping it opens the import screen:** web `/app/import`; mobile adds the type to `TYPE_ROUTE` as
  `'Import'`. A 1.0.0 build opens the app without routing, which is acceptable.
- **Push and in-app only,** no email *(my call)*.
- **Sent through the notification outbox** with a dedupe key per user per month, so a rerun never
  sends twice.
- **Its own type,** `STATEMENT_MONTH_START` (my name).

**Sid decides which switch turns it off:**
- (a) the existing FINANCIAL switch, which also silences import results; or
- (b) a new REMINDERS category, opt-out, with its own switch in both apps' notification settings.

*My recommendation: (b).* Someone tired of reminders should not lose "your statement is ready".

The activation page (2a §6) adds a count of nudges sent each month, and of users who imported
within 3 days of one.

## 7. Send a statement from your computer (item 10)

For people whose statement is on their laptop. **This is an upload code, not a sign-in** *(my call;
Sid's pick said a QR sign-in)*. A QR that signs the computer in hands it the whole account. An upload
code lets the computer add one file and see nothing. Typing a short code also needs no camera
permission.

**Flow:**

1. **On the phone,** on the import screen: "My statement is on my computer". The app asks the server
   for a code and shows it with the address `app.fynora.net/send`.
2. **On the computer,** the user opens `app.fynora.net/send` (public, no sign-in), types the code, and
   chooses one file.
   - The page shows nothing about the account: no name, no email.
   - Afterwards it says "Sent. Open Fynora on your phone to review it."
3. **The file goes through the normal queued import,** with the same type, size and refusal checks.
   It appears in the phone's Recent Imports, and the usual "ready" notice follows.
   - The phone screen showing the code updates to "Received" when the import job exists.
4. **A PDF that needs a password:**
   - The password is never typed on the public page.
   - Whether the queued path can wait for a password the user enters on the phone is **not
     established**. The builder checks first. If it cannot, the computer page refuses protected PDFs
     with "This file has a password. Share it to your phone instead, or open it on the computer and
     save a copy without the password."
   - *(That second option needs Sid's OK, since it asks users to make an unprotected copy.)*

**Security requirements:**
- The code is 6 characters from an alphabet without look-alike characters, valid 10 minutes, used
  once, and tied to the user who asked for it.
- Starting a new code cancels the old one.
- Rate limits per code and per network address. Five wrong tries from one address block it for an
  hour.
- Only the hash of the code is stored.
- Issuing and using a code are audited (user, time, address).
- The worst a stolen code can do is put one file into the owner's review queue during those 10
  minutes. Nothing is saved until the owner confirms on their phone.
- The public page and its endpoint get their own section in the PR's security self-review: CSRF,
  upload limits and edge rules (`_headers`, Cloudflare).

This is its own PR, after §3 to §6.

## 8. Recovery copy (Phase 1a)

1. **Password hint,** both apps, same words (my draft): "This is the password your bank set for the
   statement file, usually a mix of your name, PAN, date of birth or account number. It's not your
   UPI PIN, card PIN or netbanking password. Check the email the statement came in."
2. **A reason and one next action for every refusal and failure a user can hit,** in both apps, in
   the same words.
   - First an audit: list every refusal code and pre-read refusal, and drive each in the app (not
     only read the code). Record what the user sees today.
   - Then fill the gaps, in one shared map per app.
   - The existing ten entries in `importFailureMessages.ts` are the pattern.
3. **Recorded, not built:** mobile refuses multi-account statements. 2a's answers decide whether it
   becomes work.

## 9. Delivery

Each item is its own PR, in this order:

1. **Recovery copy** (§8): the audit, then the password hint and the refusal gaps.
2. **Import screen copy** (§4) and the review promise (§5.3). The card line waits for its corpus
   check.
3. **Share to Fynora** (§3): verification, any iOS fix, and the hint.
4. **The trust panel** (§5.2), after Sid's §5.1 decision and any Privacy page change.
5. **The month-start nudge** (§6), after Sid picks the switch.
6. **Send from your computer** (§7).

2a's funnel record ships before items 2 to 4, so their effect shows on the activation page.
Mobile copy goes by OTA, never to 1.0.0 builds.

## 10. Exit criteria for 2b

1. Every item in §3 to §8 is live on web and on mobile (OTA), or deliberately deferred with the reason
   written down.
2. Every sentence about files and storage is checked against code and the Privacy page, and the
   check is listed in its PR.
3. Sharing is proven by a real share on Android and on iOS.
4. The nudge is proven by a dry run against a local database: who would get it this month, and that
   a second run sends nothing.
5. The laptop upload is proven end to end, including an expired code, a reused code, a wrong code
   five times, and a second code cancelling the first.
6. Every change is verified per CLAUDE.md: tests, lint, typecheck, full backend `verify`, CI.

## 11. Calls made in this spec (for Sid to check)

- **For Sid to decide:**
  - The 90 days, (a) or (b), in §5.1.
  - The nudge's switch, (a) or (b), in §6.
  - Whether to suggest saving an unprotected copy, in §7.
- **My calls:**
  - An upload code instead of a QR sign-in (§7).
  - No bank name in nudges; push and in-app only; stopping after two months with no import (§6).
  - All copy drafts in §3, §4, §5 and §8.
  - The card line waits for a corpus check (§4).
