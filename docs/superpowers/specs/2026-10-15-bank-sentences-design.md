# Bank sentences: showing a statement's own notices in a blanked copy

Status: design, approved in conversation by the repository owner on 2026-10-15; awaiting review of
this document. This is the second half of project 4 in
`2026-10-14-fixing-statements-without-the-real-statement-design.md` ("Keep the garbage, hide the
person"). The first half, the row map, merged as #2195.

## Why

When a description swallows a page footer or a bank notice, the blanked copy shows that line as
`Xxxx xx x xxxxxxxx xxxxxxxxx`. A fixer can see from the row map that a row took an extra line,
but not that the line was "This is a computer generated statement". That is the one fact the fix
needs.

## What was checked in the code first (2026-10-15)

- **Bank words are already kept.** `PdfTraceRedactor.STRUCTURAL_WORDS` already includes the
  payment-rail words (UPI, NEFT, IMPS, RTGS, ACH, ATM, POS, ECS, NACH), page furniture ("page",
  "of", "continued", "brought", "carried", "forward") and the column and label words. So no new
  word list is needed.
- **Broken characters that can't spell anything already show.** The replacement mark "�" and box
  and other symbol characters are not letters, digits or marks, so `maskOf` copies them as they
  are.
- **Two kinds of "broken" text must stay hidden, and do today:**
  - *Private-use characters.* Some fonts (older Hindi fonts, custom-coded PDFs) store real letters
    as private-use code points. Showing them would show real text in disguise.
  - *Accented or other-script letters* such as "ÈÂÎ". They may be part of a real name.

The owner confirmed this correction in conversation before this document was written. What is
missing is the whole notice and footer lines, and that's what this design adds.

## What it does

1. **A list of bank sentences.** `PdfTraceRedactor` gains a list of exact sentences: footer and
   notice lines that identify nobody. Each entry is stored normalised: lower case, inner
   whitespace collapsed to one space, outer whitespace trimmed.
2. **Kept only as a whole printed line.** When a blanked copy is made:
   - the runs are grouped into printed lines (same page, y within the locator's
     `ROW_Y_TOLERANCE`), in x order;
   - if a line's joined text, normalised, equals a listed sentence, every run of that line is kept
     as printed;
   - if it doesn't, nothing changes: each run is masked as today.

   A line that contains a listed sentence plus anything else (a name, a number) is not a match,
   so a name can never come out inside a sentence.
3. **The redactor version goes up.** The allowlist fingerprint changes on its own when the list
   changes. Copies record both, as they already do.
4. **How the list grows.**
   - A list change is a public PR. Its only content is the sentence and the bank it was seen on.
     The owner reviews and approves it. Bank sentences are printed on every customer's statement,
     so they identify nobody, but a person still checks that each entry really is the bank's own
     wording.
   - Where entries come from:
     - **Seed (once):** a local script reads the owner's local statements and writes candidate
       lines to a file in the owner's own scratch folder, never to the repository, a chat, a log
       or a PR. A candidate is a line that has no digit, has at least 4 words, and appears
       identically in at least 3 of the statements. The owner reads the file himself and picks
       which lines to add. The script prints only counts.
     - **Afterwards:** when a blanked copy shows a masked line that looks like a footer, an Admin
       opens the real statement through the project 2 view (reason, Super Admin email, customer
       notice), reads that one line, and proposes it in a PR.

## Safety proof required before merge

- **Reading is unchanged.** `scripts/corpus-run.py` is byte-identical with `origin/main` on the
  owner's local statements, and the private trace tests pass. Only blanked copies change; no
  reading code is touched.
- **A sentence with a name attached stays masked.** For every listed sentence, a test builds the
  sentence plus a synthetic name in four positions:
  - before it in the same run;
  - after it in the same run;
  - as a separate run on the same line;
  - on the next line.

  The test expects the name masked every time, and the sentence kept only in the
  separate-next-line case.
- **The privacy checks still pass.** `TraceValidator.privacyKinds` and `copyLeaks` find nothing in
  copies made from synthetic statements that carry listed sentences.
- **The list itself is safe.** A unit test asserts that every entry has no digit, at least 4
  words, and no email or IFSC shape. A list entry can therefore never carry a reference, an
  account number or a contact.

## Measured and reported, not promised

Over the owner's local statements, before and after:
- how many printed lines a blanked copy now keeps whole;
- the blanked-copy match count (14 MATCHES / 20 DIFFERS on main, 2026-10-15).

## Done when

- The list mechanism and the safety proof are merged.
- The seed list is chosen by the owner and merged.
- The measurements are reported.

The project-level test ("a real garbage-description case fixed from the blanked copy alone")
stays open until a real held statement with that fault arrives. It is not claimed here.

## Not in scope

- A word-level row map. #2195 measured that a line-level map didn't change the match count; that
  would be its own design.
- Learning bank sentences automatically from many customers' statements. The owner chose the
  hand-made list on 2026-10-15.
- Showing ordinary English words (rejected: common names are English words).
