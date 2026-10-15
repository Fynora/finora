# Bank Sentences Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. No sub-agents: the owner said "do not use sub agents".

**Goal:** A blanked copy keeps a printed line verbatim when the whole line is a listed bank notice
or footer sentence. A name or number beside it stays masked, and reading itself doesn't change.
The owner picks the first list from candidates found on his local statements, which only he sees.

**Architecture:**
- `BankSentences` holds the normalised list, plus the invariant every entry must satisfy.
- `PdfTraceRedactor.redact` already groups runs into printed lines for digits
  (`NumberGroups.lines`). It uses the same lines to find whole-line matches, and copies those
  runs unchanged.
- `BlankedCopyComparison` learns that a token of a listed sentence inside a real description is
  kept in the copy, so copies don't start differing because of it.
- A test-tree probe plus a script write the seed candidates to a file outside the repository and
  print counts only.

**Tech Stack:** Java 25, JUnit 5, AssertJ; bash and Python 3, in the style of `scripts/corpus-run.py`.

**Spec:** `docs/superpowers/specs/2026-10-15-bank-sentences-design.md`. Its line-grouping detail
was corrected while planning: the grouping is `NumberGroups.lines`, not the locator's tolerance.

## Global Constraints

**What a sentence may be, and when it's kept**
- An entry is stored normalised: lower case (`Locale.ROOT`), inner whitespace collapsed to one
  space, trimmed.
- Every entry has no digit, at least 4 words, and no `@`. It doesn't match the IFSC shape
  `[A-Za-z]{4}0[A-Za-z0-9]{6}`, and is ASCII only. A unit test enforces all of this over the
  real list.
- A printed line is kept only when its whole joined text, normalised, equals an entry. A
  substring, prefix or suffix match keeps nothing.

**Reading must not change**
- No file under `backend/src/main/java/com/finora/imports/pdf/` changes except under
  `.../pdf/trace/`.
- `scripts/corpus-run.py` must be byte-identical with `origin/main`.
- The private trace tests (`-Pprivate-traces`) must pass with the same count.

**Versions**
- `REDACTOR_VERSION` goes from 5 to 6, with a comment line in the existing style.
- The allowlist fingerprint must include the sentence list, so a list change changes it.

**The seed**
- Candidate lines go only to a file path the owner gives. The script refuses any path inside the
  repository, and never prints a line's text: only counts.
- The seed list is added only from the entries the owner names, copied exactly from his message.

**Repository rules**
- Commit messages carry no `Co-Authored-By` or any AI-attribution trailer.
- Work only in your own worktree from `origin/main`. Never write in `/Users/sid/Downloads/finora`.
- Never print real statement content.
- `./mvnw test` skips ITs; the full run is `./mvnw verify`. Never commit while `verify` runs.

## Review Focus

1. **A listed sentence with a name glued into the same run** ("THIS IS A COMPUTER GENERATED
   STATEMENT RAHUL SHARMA"). Expected: the whole run is masked. Test in Task 2.
2. **A listed sentence split across two runs on one line.** Expected: both runs are kept. Test in
   Task 2.
3. **A listed sentence wrapped over two printed lines.** Expected: neither line matches alone, so
   both are masked. That's documented as a limit, not a bug. Test in Task 2.
4. **A footer line swallowed into a real description.** Expected: the copy keeps it verbatim in
   that row's description, the comparison treats those tokens as equal, and the verdict doesn't
   flip to DIFFERS. Test in Task 3.
5. **An empty list** (production before the seed). Expected: copies are identical to version 5
   output, apart from the version stamp. Test in Task 2.

---

### Task 1: The sentence list and its invariant

**Files:**
- Create: `backend/src/main/java/com/finora/imports/pdf/trace/BankSentences.java`
- Test: `backend/src/test/java/com/finora/imports/pdf/trace/BankSentencesTest.java`

**Interfaces:**
- Produces:
  - `static String normalise(String line)`
  - `static boolean isListed(String normalisedLine)`
  - `static List<String> entries()`, the normalised production list, empty at this task
  - `static void requireSafe(String entry)`, which throws `IllegalArgumentException` naming the
    broken rule, never the entry's text
  - `static Set<String> forTesting(String... entries)`

  Make the redactor's matcher take the set as a parameter internally, so tests can inject entries
  without touching the production list.

- [ ] **Step 1: Write the failing tests**

```java
class BankSentencesTest {
    @Test void normaliseLowersCollapsesAndTrims() {
        assertThat(BankSentences.normalise("  This  is A\tComputer   Generated Statement "))
                .isEqualTo("this is a computer generated statement");
    }
    @Test void everyProductionEntryIsSafe() {
        BankSentences.entries().forEach(BankSentences::requireSafe);
    }
    @Test void everyProductionEntryIsAlreadyNormalised() {
        BankSentences.entries().forEach(e -> assertThat(BankSentences.normalise(e)).isEqualTo(e));
    }
    @Test void anEntryWithADigitIsRefused()       { assertRefused("call us on 1800 for help today"); }
    @Test void anEntryOfThreeWordsIsRefused()     { assertRefused("computer generated statement"); }
    @Test void anEntryWithAnAtIsRefused()         { assertRefused("write to care at bank@example.com now"); }   // synthetic-ok
    @Test void anEntryShapedLikeAnIfscIsRefused() { assertRefused("branch code is abcd0xyzxyz here"); }
    @Test void aNonAsciiEntryIsRefused()          { assertRefused("this is a computer generated stätement"); }
    @Test void refusalsNameTheRuleNotTheText() {
        assertThatThrownBy(() -> BankSentences.requireSafe("call us on 1800 for help today"))
                .hasMessageNotContaining("1800").hasMessageContaining("digit");
    }

    private static void assertRefused(String entry) {
        assertThatThrownBy(() -> BankSentences.requireSafe(entry)).isInstanceOf(IllegalArgumentException.class);
    }
}
```

The IFSC test's string is synthetic. If the fixture guard flags it, mark the line `// synthetic-ok`.

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw test -Dtest=BankSentencesTest` (in `backend/`). Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package com.finora.imports.pdf.trace;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Footer and notice sentences a bank prints on every customer's statement. A blanked copy keeps a
 * printed line verbatim only when its whole text is one of these (see
 * docs/superpowers/specs/2026-10-15-bank-sentences-design.md). Entries are added by a reviewed PR
 * only, chosen by the owner; each must pass {@link #requireSafe}.
 */
public final class BankSentences {

    private BankSentences() {}

    private static final Pattern IFSC_SHAPE = Pattern.compile("[a-z]{4}0[a-z0-9]{6}");

    /** Normalised entries. Grouped by the bank each was seen on, one comment per group. */
    private static final List<String> ENTRIES = List.of(
    );

    private static final Set<String> LISTED = Set.copyOf(ENTRIES);

    public static String normalise(String line) {
        return line == null ? "" : line.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public static List<String> entries() { return ENTRIES; }

    public static boolean isListed(String normalisedLine) { return LISTED.contains(normalisedLine); }

    static Set<String> forTesting(String... entries) {
        Set<String> s = new LinkedHashSet<>();
        for (String e : entries) { requireSafe(normalise(e)); s.add(normalise(e)); }
        return s;
    }

    public static void requireSafe(String entry) {
        if (entry.codePoints().anyMatch(Character::isDigit)) throw new IllegalArgumentException("entry has a digit");
        if (entry.split(" ").length < 4) throw new IllegalArgumentException("entry has fewer than 4 words");
        if (entry.indexOf('@') >= 0) throw new IllegalArgumentException("entry has an @");
        if (!entry.chars().allMatch(c -> c < 128)) throw new IllegalArgumentException("entry is not ASCII");
        for (String word : entry.split(" ")) {
            if (IFSC_SHAPE.matcher(word).matches()) throw new IllegalArgumentException("entry has an IFSC-shaped word");
        }
    }
}
```

`Set.copyOf` of an empty list is fine. A duplicate entry would throw at class load: that's
intended, and `everyProductionEntryIsSafe` would surface it.

- [ ] **Step 4: Run them to see them pass**

Run: `./mvnw test -Dtest=BankSentencesTest`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/imports/pdf/trace/BankSentences.java backend/src/test/java/com/finora/imports/pdf/trace/BankSentencesTest.java
git commit -m "feat(imports): a reviewed list of bank notice sentences, each checked to identify nobody"
```

---

### Task 2: The redactor keeps a whole listed line

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/pdf/trace/PdfTraceRedactor.java`, in
  `redact(List<PositionedText>)` and `allowlistFingerprint()`; `REDACTOR_VERSION` becomes 6
- Modify: `backend/src/main/java/com/finora/imports/pdf/trace/NumberGroups.java`, only to make
  `lines(...)` and `Line` reachable from the redactor if they aren't already (same package, so
  they likely are)
- Test: `backend/src/test/java/com/finora/imports/pdf/trace/PdfTraceRedactorBankSentenceTest.java`

**Interfaces:**
- Consumes `BankSentences` from Task 1.
- Produces:
  - package-private `static List<PositionedText> redact(List<PositionedText> runs, Set<String> sentences)`.
    The public `redact(runs)` calls it with the production set.
  - package-private `static Set<Integer> runsOfListedLines(List<PositionedText> runs, Set<String> sentences)`,
    returning the run indexes on whole-line matches. Task 3 reuses it.

- [ ] **Step 1: Write the failing tests**

Build runs with `new PositionedText(text, x, y, page, width)`, the five-argument form `redact`
already uses. Use one synthetic sentence for all cases:
`Set<String> s = BankSentences.forTesting("This is a computer generated statement")`.

```java
@Test void aWholeLineSentenceIsKeptVerbatim()
    // one run "This is a computer generated statement" at y=700 -> text unchanged, x/width unchanged
@Test void aSentenceSplitAcrossTwoRunsOnOneLineIsKept()
    // "This is a computer" x=50 and "generated statement" x=170, same y -> both unchanged
@Test void aNameGluedIntoTheSameRunMasksTheWholeRun()
    // "This is a computer generated statement Rahul Sharma" -> fully masked as version 5 would
@Test void aNameAsAnotherRunOnTheSameLineMasksTheWholeLine()
    // sentence run + "Rahul Sharma" run at same y -> both masked
@Test void aNameBeforeTheSentenceMasksTheWholeLine()
    // "Rahul Sharma" run then sentence run, same y -> both masked
@Test void aNameOnTheNextLineLeavesTheSentenceKeptAndTheNameMasked()
    // sentence at y=700, "Rahul Sharma" at y=714 -> sentence unchanged, name masked
@Test void aSentenceWrappedOverTwoLinesIsMaskedOnBoth()
    // "This is a computer" y=700, "generated statement" y=714 -> both masked
@Test void caseAndSpacingDoNotMatter()
    // "THIS  IS A COMPUTER GENERATED STATEMENT" -> kept as printed
@Test void anEmptyListChangesNothing()
    // with Set.of(), output equals the version-5 behaviour on a synthetic statement of ~20 runs
@Test void aKeptLineStillPassesThePrivacyChecks()
    // TraceValidator.privacyKinds and copyLeaks over a formatted copy with a kept line -> nothing found
@Test void theFingerprintChangesWithTheList()
    // allowlistFingerprint over a list with and without one extra entry differ. Expose a
    // package-private fingerprint(Set<String> sentences) if needed.
```

Write each body out fully. Assert run text, x and width per run, against `redact(runs, s)`. For
`anEmptyListChangesNothing`, compare with the output on `origin/main`: generate the expected
strings once by running the version-5 redactor on that same synthetic input, and paste them as
literals with a comment saying so.

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw test -Dtest=PdfTraceRedactorBankSentenceTest`. Expected: compilation failure, then
the "kept" cases fail.

- [ ] **Step 3: Implement**

In `redact(runs, sentences)`:
1. Compute `Set<Integer> keep = runsOfListedLines(runs, sentences)` first.
2. Run the existing loop. For an index in `keep`, emit the run unchanged (text, x and width as
   printed) instead of `redactText(...)`.

`runsOfListedLines`:
1. Take `NumberGroups.lines(runs, NumberGroups.REDACTOR_GAP_MARGIN)`.
2. For each `Line`, check `BankSentences.normalise(line.text())` against `sentences`.
3. On a match, add every run index found in `line.origin()` (the `o[0]` of each non-null entry).

Check how `Line.text()` joins runs, with a space or a computed gap. If the join can produce two
spaces, `normalise` collapses them, which is what makes the split-run test pass.

`allowlistFingerprint()` must hash the sentence entries along with the vocabulary. Read how it
canonicalises the vocabulary, and append the sorted entries after a separator, only when the list
is non-empty. An empty list then leaves today's fingerprint unchanged, and no committed trace goes
stale until the seed lands.

Add the version 6 line to the comment above `REDACTOR_VERSION`: "a printed line whose whole text
is a listed bank sentence (BankSentences) is kept as printed".

- [ ] **Step 4: Run them, and the redactor's existing suites**

Run: `./mvnw test -Dtest='PdfTraceRedactor*Test,NumberGroups*Test,TraceValidator*Test,BankSentencesTest'`.
Expected: PASS. Then run the private traces. A trace records the fingerprint it was captured
under, so the corpus-health test will report every trace as captured under an older allowlist.
Read how `TraceCorpusHealthTest` treats a fingerprint mismatch. If that's a failure, the expected
fix is the one the repo uses when the allowlist changes (re-stamp or accept). Look at past
allowlist changes in `git log -S allowlistFingerprint` and follow them, and say which in the PR.

- [ ] **Step 5: Commit**

```bash
git add -A backend/src
git commit -m "feat(imports): a blanked copy keeps a printed line that is wholly a listed bank sentence"
```

---

### Task 3: The comparison treats kept sentence words as equal

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/blanked/BlankedCopyComparison.java`
  (`sameDescription`)
- Test: add cases to `backend/src/test/java/com/finora/imports/blanked/BlankedCopyComparisonTest.java`

**Interfaces:**
- Consumes the production `BankSentences` set.
- Produces a new acceptance rule (e) in `sameDescription`. The doc comment gains it, in the list
  style already there.

The rule: a copy token may equal the real token itself where the real description contains a
listed sentence (normalised, at word boundaries) covering that token's position. Tokens outside
the covered span follow rules (a) to (d) exactly as now.

- [ ] **Step 1: Write the failing tests**

```java
@Test void aSwallowedFooterKeptInTheCopyStillMatches()
    // real "UPI PAYMENT RAHUL This is a computer generated statement"
    // copy "XXX XXXXXXX XXXXX This is a computer generated statement" (as the copy would read it)
    // with the sentence injected -> sameDescription true
@Test void aSentenceWordOutsideTheSentenceSpanIsNotExcused()
    // real "computer RAHUL", copy "computer XXXXX", where "computer" is a sentence word but no whole sentence is present -> false unless (a)-(d) say true
@Test void withNoSentencesTheRulesAreAsBefore()
    // the existing tests all pass unchanged: run the whole class
```

Make `sameDescription` take the sentence set as a package-private overload, so tests inject the
synthetic sentence.

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw test -Dtest=BlankedCopyComparisonTest`.

- [ ] **Step 3: Implement**

Before the token walk, compute the covered character spans of the real description: every
occurrence of each listed sentence, matched case-insensitively with whitespace runs treated as one
space, at word boundaries. In the walk, a token whose real span lies wholly inside a covered span
is accepted when `copyPart.equals(realPart)`.

- [ ] **Step 4: Run it and the blanked-copy suites**

Run: `./mvnw test -Dtest='BlankedCopy*Test'`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A backend/src
git commit -m "fix(imports): a bank sentence kept in a blanked copy reads the same as the real description"
```

---

### Task 4: The seed candidates, for the owner only

**Files:**
- Create: `backend/src/test/java/com/finora/imports/analysis/BankSentenceCandidates.java`, a
  `main` probe in the style of `CorpusProbe`
- Create: `scripts/bank-sentence-candidates.py`, a runner in the style of `scripts/corpus-run.py`,
  reusing its classpath helper by import or by copying the function
- Test: `backend/src/test/java/com/finora/imports/analysis/BankSentenceCandidatesTest.java`
  (synthetic PDFs only)

**Interfaces:**
- Produces: `python3 scripts/bank-sentence-candidates.py <corpus-dir> -o <out-file>`
  - It refuses with exit 2 if `<out-file>` or `<corpus-dir>` is inside the repository (resolve
    both, and compare with `git rev-parse --show-toplevel`).
  - It writes `<statements>\t<bank-or-unknown>\t<normalised line>` per candidate, most common
    first.
  - It prints only `statements read: N, lines considered: M, candidates: K`.

The candidate rule, exactly as the spec says: a printed line (`NumberGroups.lines` over the
statement's acquired runs, the same acquisition the blanked copy uses) whose normalised text has
no digit and at least 4 words, and appears in at least 3 different statement files. Also apply
`BankSentences.requireSafe`, and skip anything it refuses. `<bank>` is the bank detected for each
statement (use the detector the pipeline uses; find it via `BankRegistry`). Where the files carry
different banks, write them comma-joined.

- [ ] **Step 1: Write the failing probe test**

Make four synthetic PDFs with PDFBox, in a temp dir:
- three of them carry the line "Please examine this statement upon receipt" and a different
  synthetic name line each;
- the fourth carries a different notice.

Run the probe's static `candidates(Path dir, int minStatements)`. Expect exactly one candidate,
with count 3, and no name lines.

- [ ] **Step 2: Run it to see it fail, implement, run it to see it pass**

Run: `./mvnw test -Dtest=BankSentenceCandidatesTest`.

- [ ] **Step 3: Write the runner and check its refusal**

`python3 scripts/bank-sentence-candidates.py /tmp/x -o ./inside.txt` must exit 2. Add a case
to `scripts/test-corpus-run.py`'s style of script test if one exists for this kind of guard;
otherwise add `scripts/test-bank-sentence-candidates.py` with that one case.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/finora/imports/analysis scripts/bank-sentence-candidates.py scripts/test-bank-sentence-candidates.py
git commit -m "chore(imports): list candidate bank sentences from local statements into a private file"
```

- [ ] **Step 5: Run it for the owner, and stop**

1. Run it over the owner's local corpus (the same directory `corpus-run.py` uses), writing to a
   file in the owner's own scratch area outside the repo. Use
   `~/fynora-private/bank-sentence-candidates.txt`, creating the folder; tell him the path.
2. Tell the owner only:
   - the path;
   - the three counts the script printed;
   - that he should reply with the lines to add, copied exactly, or "none".
3. Wait for his reply. Don't open, print or summarise the file's contents yourself.

---

### Task 5: The owner's seed list

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/pdf/trace/BankSentences.java` (`ENTRIES`)

- [ ] **Step 1: Add exactly the lines the owner sent**

Normalise each. Group them under a one-line comment per bank, as the candidate file named it.
`BankSentencesTest` must still pass. If an entry fails `requireSafe`, don't add it: tell the owner
which rule it broke, without quoting it, and ask.

If the owner replied "none", skip to Task 6 with an empty list.

- [ ] **Step 2: Run the tests and commit**

```bash
./mvnw test -Dtest='BankSentencesTest,PdfTraceRedactor*Test'
git add backend/src/main/java/com/finora/imports/pdf/trace/BankSentences.java
git commit -m "feat(imports): the first bank sentences, chosen by the owner"
```

---

### Task 6: Measure, verify, PR

- [ ] **Step 1: Reading unchanged**

`corpus-run.py` must be byte-identical with `origin/main`, and the private trace tests must pass
with the same count, with the fingerprint handling from Task 2.

- [ ] **Step 2: The two measurements**

Over the owner's local statements, counts only:
1. lines kept whole by a bank sentence, in total and per statement count;
2. blanked-copy MATCHES / DIFFERS / NOT_CHECKED, against 14 / 20 / 0 on `origin/main`.

A drop in MATCHES is a regression: read which field and row changed, and fix it before going on.

- [ ] **Step 3: Full runs**

Run backend `./mvnw verify -Pprivate-traces -Dfinora.private.repo=<clone>`. Record the counts.

- [ ] **Step 4: Self-review for leaks**

Read every new log line, exception message and test fixture in `git diff origin/main`. No real
statement text may appear. The list entries are the owner's choice, and nothing else from his
statements.

- [ ] **Step 5: PR**

Run `git fetch origin && git log --oneline origin/main..HEAD`, then push and open the PR. The body
covers:
- what changed;
- the version bump and fingerprint handling;
- the measurements;
- the number of seed entries, and that the owner chose them;
- the known limit: a sentence wrapped over two lines isn't kept.

Don't merge.
