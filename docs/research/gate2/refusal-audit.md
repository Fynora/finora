# What a user sees today when an import is refused

Gate 2b, PR 1, Task 1 (spec `docs/superpowers/specs/2026-10-16-gate2b-remove-barriers-design.md` §8).
Audited 2026-10-24 against `main` at `9ada9882a`.

Codes and observed screens only. Every file used was synthetic (a fake bank, placeholder numbers),
made for this audit and not kept.

## How it was driven

- **Backend:** this commit's jar, `dev` profile, on a throwaway Postgres and Redis. Statement storage
  on the local filesystem; ClamAV (`clamav/clamav-debian`) as the malware scanner. Run twice: with
  the import queue off (`app.import.queue.enabled=false`, the code default, so uploads go to the
  synchronous `/import/{csv,pdf}/stage` endpoints) and with it on (`/import/jobs` and the worker).
  Whether production runs the queue is not established from the repository, so both are recorded.
- **Web:** Vite dev server from this commit, driven in Chrome. Files chosen through the page's file
  input.
- **Mobile:** the iOS dev client (iPhone 17 Pro simulator, iOS 26.5) on a Metro bundle from this
  commit, pointed at the same local backend. Files chosen through the system picker from On My
  iPhone.
- **Not driven on mobile, and why:** each pick through the system Files sheet costs several
  simulator round trips, so mobile was driven for one case of each display path (a client refusal,
  a server refusal, a queued failure, the password prompt, the failed-imports list). Both apps send a
  server refusal through the same rule: the curated message from `importFailureMessages.ts` when the
  code has one, otherwise the server's `message`. Those rows are marked *same path*, with the driven
  case they share it with.

## The two display paths

1. **Refused at upload** (the HTTP request fails). Web shows a banner above the page
   (`Import.tsx`, `upload()` catch); mobile shows a banner at the top of the import card
   (`ImportScreen.tsx`, `upload()` catch). Text = curated message for the code, else the server's
   `message`, else a fallback.
2. **Failed in the queue** (the job ends FAILED). Web `ImportTimeline` and mobile
   `ImportProgressCard` show the curated title and message, then "Try a different file". The
   failed-imports lists (web `retryState` line, mobile "Recent failed imports") show the curated
   message only, else "Fynora couldn't complete this import."

## Results

"Next action" is what the words tell the user to do. "None" means the text gives a reason at most.

| # | Code or trigger | Web: what the user sees | Mobile: what the user sees | Next action offered |
|---|---|---|---|---|
| 1 | Client: file is not .csv or .pdf | "Please upload a .csv or .pdf bank/credit card statement." *(driven)* | "Choose a .csv or .pdf bank or credit card statement." *(driven)* | Choose a .csv or .pdf. **The two apps use different words.** |
| 2 | `BAD_REQUEST`: empty file (`StatementUpload.requireReadable`) | "No file was uploaded, or the file is empty." *(driven, both paths)* | Same server message *(same path as #3)* | **None** |
| 3 | `UNSUPPORTED_MEDIA_TYPE`: a .pdf that is not a PDF | "This endpoint reads PDF statements, and this file is not a PDF. If it is a CSV or spreadsheet export, upload it as a CSV instead." *(driven, both paths)* | Same *(driven)* | Upload as CSV. **Says "endpoint".** |
| 4 | `UNSUPPORTED_MEDIA_TYPE`: a .csv that is a PDF | "This file is a PDF, and this endpoint reads CSV statements. Upload it as a PDF statement instead." *(server response read; not driven in an app)* | Same *(same path as #3)* | Upload as PDF. **Says "endpoint".** On the queue a .csv name always means CSV, so "upload it as a PDF" is not something the user can do without renaming the file. |
| 5 | `UPLOAD_TOO_LARGE` (over 10 MB) | "That file is too large to upload. Try splitting the statement, or upload it as a CSV export instead." *(driven)* | Same server message *(same path as #3)* | Split it, or use CSV |
| 6 | `UPLOAD_MALWARE_DETECTED` (EICAR test file) | "This file was rejected by the malware scanner and was not processed." *(driven)* | Same *(same path as #3)* | **None** |
| 7 | `UPLOAD_SCANNER_UNAVAILABLE` (ClamAV stopped) | "Uploads are paused while the malware scanner is unreachable. Please try again in a few minutes." *(driven, after the 25 s retry window)* | Same *(same path as #3)* | Try again in a few minutes |
| 8 | `IMPORT_006` system busy (`max-concurrent=0`) | "Finora is processing a lot of statement imports right now. Please try again in a moment." *(driven)* | Same *(same path as #3)* | Try again. **Says "Finora", the old name.** |
| 9 | `RATE_LIMITED` (11th upload in 10 minutes from one address) | "Too many requests. Please wait before trying again." *(driven)* | Same *(same path as #3)* | "Wait", with no sense of how long or why |
| 10 | Server down behind the proxy (502, no JSON body) | "Could not parse this CSV." *(driven)* | "Something went wrong on our end. Try again shortly." *(read from `toUserMessage`; same path as #3)* | **Web: none, and it blames the file.** Mobile: try again |
| 11 | No response at all (connection error) | "Unable to reach the import service. The upload request could not be completed — check your connection and try again." *(driven by failing the request in the page)* | "Can't reach Fynora. Check your connection and try again." *(read from `toUserMessage`)* | Check connection, try again |
| 12 | `IMPORT_008` password required | Password field opens: "This statement is password protected. Enter the password your bank uses for it." *(driven)* | Same field and words *(driven)* | Enter the password |
| 13 | `IMPORT_009` wrong password, at upload | Inline under the field: "That password didn't open this statement — check it and try again." *(driven)* | Same *(read; same branch as #12)* | Check it, try again |
| 14 | `IMPORT_009` in a failed-imports list | — | "Fynora couldn't complete this import." *(driven: listed after #13)* | **None. `IMPORT_009` has no curated message, so the list falls back.** Web's `retryState` line uses the same lookup |
| 15 | `IMPORT_001` no table | Curated message *(driven, sync)* | Title "This doesn't look like a statement" + message + "Choose a different file" *(driven, queue)* | Check it is the transaction statement |
| 16 | `IMPORT_007` table, no rows (a CSV) | Curated message *(driven)*: "…the transaction statement **PDF** from your bank…" | Same *(same path as #15)* | Check the file. **Says PDF for a CSV.** |
| 17 | `IMPORT_010` scanned PDF | Curated message *(driven)* | Same *(same path as #15)* | Use a downloaded statement |
| 18 | `IMPORT_011` corrupt PDF | Curated message *(driven)* | Same *(same path as #15)* | Download again |
| 19 | `IMPORT_017` malformed CSV | Curated message *(driven, both paths)* | Same *(driven, in the failed list)* | Download again |
| 20 | `IMPORT_018` payment app history | Curated message *(driven)* | Same *(same path as #15)* | Import the bank statements |
| 21 | `IMPORT_020` several accounts in one CSV | Curated message *(driven)* | Same *(same path as #15)* | Upload each statement on its own |
| 22 | `IMPORT_013` too many pages (501) | Curated message *(driven)* | Same *(same path as #15)* | Split it |
| 23 | `IMPORT_014` no activity | **Not driven:** only a located PDF ledger row with "Transaction Count" and zero on both sides triggers it; a CSV row with those values imports as a row (tried). Curated message exists | Same | None needed: nothing to import |
| 24 | `IMPORT_015` trust review rejected | **Not driven:** needs an admin to reject a held job. Curated message: "…Nothing was added to your accounts." | Same | **None, by Sid's decision of 2026-09-04** (recorded on `ErrorCode.IMPORT_TRUST_REVIEW_REJECTED`) |
| 25 | `IMPORT_016` held for review | Not a refusal: the hold card | Same | — |
| 26 | `IMPORT_019` re-import untrusted | **Not driven:** re-import only. Server message: "…Your existing transactions are unchanged." | Same | None. Out of scope: not an upload |
| 27 | `ENTITLEMENT_003` Free plan, statement over a month | Banner naming the dates, "See Plus plans" *(driven)* | Read: same server message | Upgrade, or upload one month |
| 28 | `ENTITLEMENT_002` Free plan, third account, at confirm | "Free plan is limited to 2 accounts. Upgrade to Plus for unlimited accounts." + "See Plus plans" *(driven)* | Read: same server message | Upgrade |
| 29 | Mobile: statement covering several accounts | — | "This statement covers more than one account. Multi-account statements can only be imported from the Fynora web app for now." *(read)* | Use the web app. Recorded, not built (spec §8.3) |
| 30 | Unknown job failure | "Fynora couldn't complete this import. Please try again." *(read)* | Same *(read)* | Try again |

## The password hint today

- Web: "Many banks protect statements with a password — often a mix of your name, PAN, date of birth
  or account number. Check the email the statement came in."
- Mobile: the same, ending "Check the email it came in."
- Neither says what the password is not (spec §1).

## Gaps PR 1 fills

Rows with no next action, or whose words mislead:

- **#1** the two apps disagree;
- **#2** empty file;
- **#3, #4** "endpoint", and #4's advice cannot be followed;
- **#6** malware;
- **#8** "Finora";
- **#9** "wait", with no reason;
- **#10** web blames the file for a server failure;
- **#14** wrong password in the failed-imports lists.

Not changed here, and why:

- **#16** says "PDF" for a CSV, and #15 does too. That is existing curated copy; changing it is a
  question for Sid, asked in the PR.
- **#24** has no next action by Sid's own decision.
- **#26** is a re-import, not an upload.
