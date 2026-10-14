# Blanked Copy From the Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Staff can download a blanked copy of a held PDF statement from the admin portal, made on
the server, with a check saying whether the copy reads the same as the real statement, while the
real statement never leaves the server.

**Architecture:** The redaction classes move from test code into
`com.finora.imports.pdf.trace`. A new `com.finora.imports.blanked` package holds the comparison
and the service that acquires text once, redacts it, runs a privacy-only refusal check, formats the
trace, and replays both the real runs and the trace file's own round trip through
`PdfPreviewGenerator` with a fresh random user id (so no customer's rules, merchants or duplicates
are read). Each held queue's service gains an audited, gated "content for a blanked copy" method; a
new controller exposes one endpoint per queue plus a status endpoint behind a switch.

**Tech Stack:** Java 25, Spring Boot, JUnit 5, AssertJ, Mockito, Testcontainers ITs
(`AbstractIntegrationTest`), Micrometer; admin portal React + TypeScript, TanStack Query, Vitest,
Testing Library. Node 22 for the admin portal (`npx node@22` or the worktree's Node 22 path).

**Spec:** `docs/superpowers/specs/2026-10-14-fixing-statements-without-the-real-statement-design.md`
(project 1).

## Global Constraints

- The real bytes and unredacted runs never leave `BlankedCopyService`; nothing from them appears in
  a response, log line, exception message, error report or audit entry.
- A copy is refused only by the privacy scan (email, Indian mobile number, full IFSC). The
  validator's "no table found" blocker must never refuse a server copy: never-seen layouts are the
  main use.
- A mismatch report names fields and row numbers, and only masked tokens already present in the
  copy. Never a real value.
- The customer's file name never appears in the copy, its metadata, or the download name. The
  download name is `blanked-<reference>.trace`.
- Switch `app.blanked-copy.enabled`, default `false`. Off: both copy endpoints return 404 and the
  status endpoint returns `{ "enabled": false }`.
- Endpoints are gated by the queue permission only (`IMPORT_TRIAGE_MANAGE`,
  `TRUST_REVIEW_MANAGE`), not by the ADMIN role. Only held items.
- Audit actions: `HELD_IMPORT_BLANKED_COPY` (entity `ImportJob`) and
  `HELD_STATEMENT_BLANKED_COPY` (entity `HeldStatement`), written with `recordEvenOnRollback`
  before any bytes are read. Details: ids and `savedPasswordOnFile` only.
- PDF only. A CSV item: 409 "Blanked copy not available for CSV yet."
- Commit messages carry no `Co-Authored-By` or any AI-attribution trailer.
- Tests that plant customer-shaped values (a phone number, a full IFSC, an email) on purpose mark
  each such line `// synthetic-ok`, or the pre-commit fixture guard blocks the commit. Prefer the
  guard's own placeholder forms (`100000000001`, `UTIB0XXXXXX`) wherever the test does not need a
  value the privacy scan must catch.
- Work only in `/Users/sid/Downloads/finora/.claude/worktrees/balance-chain-diagnostics` on branch
  `feature/statement-blanked-copy`. Never write in `/Users/sid/Downloads/finora`.
- Never print a real statement's content. Local real-corpus measurements report counts only.
- `./mvnw test` skips ITs; the full run is `./mvnw verify`. Never commit while `verify` is running.

## Review Focus

1. **A scanned (OCR) statement.** The trace format drops each run's source and confidence, so the
   copy side reads as native text. Expected: a copy is still produced and the check says MATCHES or
   DIFFERS, never an error. Test in Task 4 (`ocrRunsStillProduceACopyAndAVerdict`).
2. **A statement with no recognisable table** (a never-seen layout). Expected: a copy is produced,
   not refused. Test in Task 4 (`aLayoutWithNoTableStillGetsACopy`).
3. **A PDF with no text at all.** Expected: a copy with zero runs and MATCHES, not a crash. Test in
   Task 4 (`aDocumentWithNoTextGetsAnEmptyCopy`).
4. **A held item decided between page load and click.** Expected: 409, no copy, and the audit entry
   still written. Test in Task 6 (`aReleasedItemIsRefused`).
5. **A real-value leak through a DIFFERS report.** A run the redactor masks (a phone number) that
   the real reading treats as an amount. Expected: the report says AMOUNT differs on that row and
   carries no digits from the real run. Test in Task 3 (`differencesCarryNoRealValues`).

---

### Task 1: Move the redaction classes into main code

**Files:**
- Create: `backend/src/main/java/com/finora/imports/pdf/trace/PdfTraceRedactor.java` (moved from
  `backend/src/test/java/com/finora/imports/pdf/fixtures/PdfTraceRedactor.java`)
- Create: `backend/src/main/java/com/finora/imports/pdf/trace/TraceMetadata.java` (moved)
- Create: `backend/src/main/java/com/finora/imports/pdf/trace/TraceValidator.java` (moved)
- Create: `backend/src/main/java/com/finora/imports/pdf/trace/TraceFormat.java` (the `format` and
  `parse` halves of `PdfTrace`)
- Modify: `backend/src/test/java/com/finora/imports/pdf/fixtures/PdfTrace.java` (keeps
  `load`, `read`, `metadata(String)`, `committedTraceNames`; `format`/`parse` delegate to
  `TraceFormat`)
- Move tests: `PdfTraceRedactorTest.java`, `TraceValidatorTest.java` to
  `backend/src/test/java/com/finora/imports/pdf/trace/` (package-private access still works)
- Modify imports in: `CapabilityCorpusCoverageTest`, `analysis/Pass2CorpusFixtures`,
  `pdf/AccountIdentityLinePdfTableLocatorTest`, `pdf/PdfTextExtractorSnapshotTest`,
  `pdf/PdfPipelineDiagnostic`, `pdf/TraceFixtureRegressionTest`, `pdf/GoldenOutputSnapshotTest`,
  `pdf/fixtures/TraceQualityReport`, `pdf/fixtures/PdfFixtureBuilder`,
  `pdf/fixtures/TraceWidthFidelityTest`, `pdf/fixtures/TraceCorpusHealthTest`,
  `product/FinancialProductClassifierTest`
- `TraceQualityReport` stays in test code (only the capture diagnostic uses it).

**Interfaces:**
- Produces:
  - `PdfTraceRedactor.redact(List<PositionedText>) : List<PositionedText>` (unchanged)
  - `PdfTraceRedactor.redactLine(String) : String` (new; same masking as one run's text)
  - `PdfTraceRedactor.REDACTOR_VERSION`, `PdfTraceRedactor.allowlistFingerprint()` (unchanged)
  - `TraceFormat.format(List<PositionedText>, TraceMetadata) : String`
  - `TraceFormat.parse(String) : List<PositionedText>`
  - `TraceMetadata` record, unchanged; `TraceMetadata.CURRENT_TRACE_VERSION`
  - `TraceValidator.validate(String, String) : Result` (unchanged)
  - `TraceValidator.privacyKinds(List<PositionedText>) : List<String>` (new; distinct kinds from
    `{"email address", "phone number", "IFSC branch code"}`, never the matched text)

- [ ] **Step 1: Record the before-state of the trace suite**

Run (from `backend/`):
```bash
./mvnw -q test -Dtest='PdfTraceRedactorTest,TraceValidatorTest,TraceCorpusHealthTest,TraceWidthFidelityTest,GoldenOutputSnapshotTest,TraceFixtureRegressionTest,CapabilityCorpusCoverageTest,FinancialProductClassifierTest,AccountIdentityLinePdfTableLocatorTest,PdfTextExtractorSnapshotTest' -DfailIfNoTests=false
```
Save the per-class "Tests run" lines (from `target/surefire-reports/*.txt`) to the scratchpad as
`trace-suite-before.txt`. Expected: all pass.

- [ ] **Step 2: Move the files with git, so history follows them**

```bash
git mv backend/src/test/java/com/finora/imports/pdf/fixtures/PdfTraceRedactor.java backend/src/main/java/com/finora/imports/pdf/trace/PdfTraceRedactor.java
git mv backend/src/test/java/com/finora/imports/pdf/fixtures/TraceMetadata.java backend/src/main/java/com/finora/imports/pdf/trace/TraceMetadata.java
git mv backend/src/test/java/com/finora/imports/pdf/fixtures/TraceValidator.java backend/src/main/java/com/finora/imports/pdf/trace/TraceValidator.java
git mv backend/src/test/java/com/finora/imports/pdf/fixtures/PdfTraceRedactorTest.java backend/src/test/java/com/finora/imports/pdf/trace/PdfTraceRedactorTest.java
git mv backend/src/test/java/com/finora/imports/pdf/fixtures/TraceValidatorTest.java backend/src/test/java/com/finora/imports/pdf/trace/TraceValidatorTest.java
```
Change each moved file's `package` line to `package com.finora.imports.pdf.trace;`.

- [ ] **Step 3: Split `PdfTrace` into `TraceFormat` (main) and the test-side loader**

Create `TraceFormat.java` in `com.finora.imports.pdf.trace` holding `PdfTrace`'s `format(List)`,
`format(List, TraceMetadata)` and `parse(String)` bodies verbatim, with their doc comments and
private helpers. In the test-side `PdfTrace`, replace those three bodies with delegation:

```java
public static String format(List<PositionedText> runs) { return TraceFormat.format(runs); }
public static String format(List<PositionedText> runs, TraceMetadata metadata) { return TraceFormat.format(runs, metadata); }
public static List<PositionedText> parse(String content) { return TraceFormat.parse(content); }
```
In `TraceValidator.validate`, replace `PdfTrace.parse(content)` with `TraceFormat.parse(content)`.

- [ ] **Step 4: Fix imports and compile**

In every file listed under "Modify imports", replace
`com.finora.imports.pdf.fixtures.PdfTraceRedactor|TraceMetadata|TraceValidator` with
`com.finora.imports.pdf.trace.…`; in the `fixtures` package files that used them unqualified, add
the imports. Run: `./mvnw -q test-compile`. Expected: BUILD SUCCESS.

- [ ] **Step 5: Write the failing tests for the two new methods**

In `backend/src/test/java/com/finora/imports/pdf/trace/PdfTraceRedactorTest.java` add:

```java
@Test
void redactLineMasksExactlyAsARunWouldBe() {
    String line = "UPI Asha Verma 9876543210 1,234.56 12/07/2026 Balance"; // synthetic-ok
    PositionedText run = new PositionedText(line, 0f, 0f, 0, 10f);
    assertThat(PdfTraceRedactor.redactLine(line))
            .isEqualTo(PdfTraceRedactor.redact(List.of(run)).get(0).text());
    assertThat(PdfTraceRedactor.redactLine(line)).doesNotContain("Asha", "Verma", "9876543210"); // synthetic-ok
    assertThat(PdfTraceRedactor.redactLine(line)).contains("1,234.56", "12/07/2026", "Balance");
}
```
In `TraceValidatorTest.java` add:

```java
@Test
void privacyKindsNamesTheKindNeverTheValue() {
    List<PositionedText> runs = List.of(
            new PositionedText("someone@example.org", 0f, 0f, 0),
            new PositionedText("9876543210", 0f, 10f, 0), // synthetic-ok
            new PositionedText("HDFC0001234", 0f, 20f, 0)); // synthetic-ok
    List<String> kinds = TraceValidator.privacyKinds(runs);
    assertThat(kinds).containsExactlyInAnyOrder("email address", "phone number", "IFSC branch code");
    assertThat(String.join(" ", kinds)).doesNotContain("someone", "98765", "0001234");
}

@Test
void privacyKindsIsEmptyForATraceWithNoTable() {
    List<PositionedText> runs = List.of(new PositionedText("Xxxxx xxxx", 0f, 0f, 0));
    assertThat(TraceValidator.privacyKinds(runs)).isEmpty();
}
```
Before writing the IFSC case, confirm the validator's own placeholder rule does not exempt
`HDFC0001234`; if it does, use the shape the rule does not exempt (read `TraceValidator`'s (synthetic-ok)
`PLACEHOLDER` pattern) and keep the assertion.

- [ ] **Step 6: Run them to verify they fail**

Run: `./mvnw -q test -Dtest='PdfTraceRedactorTest#redactLineMasksExactlyAsARunWouldBe+TraceValidatorTest#privacyKinds*'`
Expected: compilation failure, `redactLine` and `privacyKinds` not found.

- [ ] **Step 7: Implement**

In `PdfTraceRedactor`:
```java
/** One line of text masked exactly as a run with this text would be by {@link #redact}. */
public static String redactLine(String text) {
    return redactText(text, vocabulary());
}
```
In `TraceValidator`, beside `scanForPii`:
```java
/**
 * Which kinds of customer data survived redaction, by name only. The server's refusal check:
 * unlike {@link #validate}, a layout with no table is not a reason to refuse, and the matched
 * text is never returned, so a refusal can be shown to staff without showing the data.
 */
public static List<String> privacyKinds(List<PositionedText> runs) {
    LinkedHashSet<String> kinds = new LinkedHashSet<>();
    for (PositionedText run : runs) {
        String text = run.text();
        if (text == null || text.isBlank()) continue;
        if (matchesUnplaceholdered(EMAIL, text)) kinds.add("email address");
        if (matchesUnplaceholdered(INDIAN_MOBILE, text)) kinds.add("phone number");
        if (hasUnmaskedIfsc(text)) kinds.add("IFSC branch code");
    }
    return List.copyOf(kinds);
}
```
Implement `matchesUnplaceholdered` and `hasUnmaskedIfsc` by extracting the match-and-exemption
logic `checkPattern` and `checkIfsc` already use, and make `checkPattern`/`checkIfsc` call them,
so the two paths cannot disagree.

- [ ] **Step 8: Run the new tests and the whole trace suite**

Run the Step 1 command plus the two new tests. Expected: all pass, and every class's
"Tests run" count equals `trace-suite-before.txt` (plus the 3 new tests).

- [ ] **Step 9: Check the move is a move**

Run: `git diff --cached -M --stat` after `git add -A backend`. Expected: the three moved main
files and two moved tests show as renames with only package, import and the new-method lines
changed. Run `grep -rn "pdf.fixtures.PdfTraceRedactor\|pdf.fixtures.TraceValidator\|pdf.fixtures.TraceMetadata" backend scripts` . Expected: no output.

- [ ] **Step 10: Commit**

```bash
git commit -m "refactor(imports): the statement redactor and its privacy check move into main code"
```

### Task 2: A reading entry point that starts from acquired text

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/pdf/PdfPreviewGenerator.java:189-195`
- Test: `backend/src/test/java/com/finora/imports/ReadingFromAcquiredTextTest.java` (package
  `com.finora.imports`, to use `PdfGeneratorTestSupport.generator()`)

**Interfaces:**
- Produces:
  - `PdfPreviewGenerator.acquire(byte[] fileBytes, String password) : AcquiredDocument` (throws
    `IOException`)
  - `PdfPreviewGenerator.generateSectionsFromAcquired(UUID userId, String filename, AcquiredDocument acquired) : PdfGenerationResult`

- [ ] **Step 1: Write the failing test**

```java
package com.finora.imports;

import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ReadingFromAcquiredTextTest {

    static Stream<Callable<byte[]>> samples() {
        return Stream.of(
                PdfFixtureBuilder::buildReverseChronologicalRunningBalanceSample,
                PdfFixtureBuilder::buildReferenceNumberAndBalanceSample,
                PdfFixtureBuilder::buildWrappedDescriptionCreditCardSample,
                PdfFixtureBuilder::buildMultiSectionCompositeStatementSample,
                PdfFixtureBuilder::buildSavingsAndCardWithCardSummarySample);
    }

    @ParameterizedTest
    @MethodSource("samples")
    void readingFromAcquiredTextEqualsReadingFromBytes(Callable<byte[]> sample) throws Exception {
        byte[] pdf = sample.call();
        PdfPreviewGenerator generator = PdfGeneratorTestSupport.generator();
        UUID user = UUID.randomUUID();

        var fromBytes = generator.generateSectionsWithContext(user, "statement.pdf", pdf).sections();
        var fromAcquired = generator.generateSectionsFromAcquired(user, "statement.pdf",
                generator.acquire(pdf, null)).sections();

        assertThat(fromAcquired).isEqualTo(fromBytes);
    }
}
```
If a `StagedAccountSection` field has no value equality (for example a mutable map), compare
`toString()` of both lists instead and say so in a comment.

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -q test -Dtest=ReadingFromAcquiredTextTest`
Expected: compilation failure, `acquire` and `generateSectionsFromAcquired` not found.

- [ ] **Step 3: Implement by splitting the existing method at acquisition**

```java
/** Text acquisition alone, with the same acquirer an import uses. */
public com.finora.imports.pdf.acquisition.AcquiredDocument acquire(byte[] fileBytes, String password) throws IOException {
    return textAcquirer.acquire(fileBytes, password);
}

public PdfGenerationResult generateSectionsWithContext(UUID userId, String filename, byte[] fileBytes, String password) throws IOException {
    return generateSectionsFromAcquired(userId, filename, acquire(fileBytes, password));
}

/**
 * Everything after acquisition: the whole reading, staging and verification, from text already
 * acquired. The blanked-copy check reads the real runs and a blanked copy's runs through exactly
 * this, so the two readings differ only in their text.
 */
public PdfGenerationResult generateSectionsFromAcquired(UUID userId, String filename,
        com.finora.imports.pdf.acquisition.AcquiredDocument acquired) {
    DocumentContext ctx = new DocumentContext("PDF", "PdfPreviewGenerator");
    List<PositionedText> positioned = acquired.runs();
    // ... the existing body from `ctx.recordExtractedRuns(positioned.size());` to the final
    // `return new PdfGenerationResult(result, ctx, printedCreditCardSummary);`, unchanged ...
}
```
Keep every comment of the moved body. If the moved body throws `IOException` anywhere, keep
`throws IOException` on `generateSectionsFromAcquired`.

- [ ] **Step 4: Run the test and the PDF generator suites**

Run: `./mvnw -q test -Dtest='ReadingFromAcquiredTextTest,*PdfPreviewGeneratorTest,MultiSectionZeroExtractionTest,PaymentAppHistoryDocumentTest,ParserIndependencePreviewGeneratorTest'`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/imports/pdf/PdfPreviewGenerator.java backend/src/test/java/com/finora/imports/ReadingFromAcquiredTextTest.java
git commit -m "refactor(imports): a PDF can be read from text already acquired"
```

### Task 3: The comparison

**Files:**
- Create: `backend/src/main/java/com/finora/imports/blanked/BlankedCopyComparison.java`
- Test: `backend/src/test/java/com/finora/imports/blanked/BlankedCopyComparisonTest.java`

**Interfaces:**
- Consumes: `ImportDto.StagedAccountSection`, `StagedRow`, `DetectedAccountInfo`,
  `VerificationReport`, `VerificationFinding`; `PdfTraceRedactor.redactLine`.
- Produces:
  - `enum BlankedCopyComparison.Field { SECTION_COUNT, ROW_COUNT, UNPARSEABLE_COUNT, DATE, AMOUNT, DIRECTION, BALANCE, DESCRIPTION, PERIOD_START, PERIOD_END, OPENING_BALANCE, CLOSING_BALANCE, FINDINGS }`
  - `record BlankedCopyComparison.Difference(int section, Integer row, Field field, List<String> maskedTokens)` (`row` null for section-level fields; `maskedTokens` empty except for DESCRIPTION)
  - `record BlankedCopyComparison.Result(List<Difference> differences) { boolean matches() }`
  - `static Result compare(List<StagedAccountSection> real, List<StagedAccountSection> copy)`

- [ ] **Step 1: Write the failing tests**

```java
package com.finora.imports.blanked;

import com.finora.dto.ImportDto.*;
import com.finora.imports.blanked.BlankedCopyComparison.Field;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BlankedCopyComparisonTest {

    // Builders: read ImportDto's StagedRow, DetectedAccountInfo, StagedAccountSection and
    // VerificationReport canonical constructors and write row(...), section(...) helpers here that
    // set only date, description, amount, type, balanceAfter, suggestedCategory, the account's
    // name, number, period and opening/closing balance, and the findings; every other component
    // null, zero or empty.

    @Test
    void identicalReadingsMatch() {
        var real = List.of(section(List.of(row("2026-07-01", "UPI Asha Verma", "100.00", "DEBIT", "900.00"))));
        var copy = List.of(section(List.of(row("2026-07-01", "UPI Xxxx Xxxxx", "100.00", "DEBIT", "900.00"))));
        assertThat(BlankedCopyComparison.compare(real, copy).matches()).isTrue();
    }

    @Test
    void eachRowFieldIsReported() {
        var real = List.of(section(List.of(row("2026-07-01", "UPI Asha", "100.00", "DEBIT", "900.00"))));
        var copy = List.of(section(List.of(row("2026-07-02", "UPI Xxxx Xxxx", "101.00", "CREDIT", "901.00"))));
        assertThat(BlankedCopyComparison.compare(real, copy).differences())
                .extracting(BlankedCopyComparison.Difference::field)
                .containsExactlyInAnyOrder(Field.DATE, Field.DESCRIPTION, Field.AMOUNT, Field.DIRECTION, Field.BALANCE);
    }

    @Test
    void rowAndSectionCountsAreReportedWithoutComparingRowsPastTheShorterList() {
        var real = List.of(section(List.of(row("2026-07-01", "A", "1.00", "DEBIT", "9.00"),
                row("2026-07-02", "B", "1.00", "DEBIT", "8.00"))));
        var copy = List.of(section(List.of(row("2026-07-01", "X", "1.00", "DEBIT", "9.00"))), section(List.of()));
        assertThat(BlankedCopyComparison.compare(real, copy).differences())
                .extracting(BlankedCopyComparison.Difference::field)
                .contains(Field.SECTION_COUNT);
    }

    @Test
    void periodBalancesAndFindingsAreReported() {
        var real = List.of(sectionWith("2026-07-01", "2026-07-31", "1000.00", "900.00", "BALANCE_CHAIN", "VERIFIED"));
        var copy = List.of(sectionWith("2026-07-02", "2026-07-30", "1001.00", "901.00", "BALANCE_CHAIN", "WARNING"));
        assertThat(BlankedCopyComparison.compare(real, copy).differences())
                .extracting(BlankedCopyComparison.Difference::field)
                .containsExactlyInAnyOrder(Field.PERIOD_START, Field.PERIOD_END, Field.OPENING_BALANCE,
                        Field.CLOSING_BALANCE, Field.FINDINGS);
    }

    @Test
    void accountIdentityCategoryAndDuplicatesAreNotCompared() {
        var real = List.of(sectionNamed("Asha Verma", "XXXX1234", "Groceries"));
        var copy = List.of(sectionNamed("Xxxx Xxxxx", "XXXX9999", "Uncategorized"));
        assertThat(BlankedCopyComparison.compare(real, copy).matches()).isTrue();
    }

    @Test
    void differencesCarryNoRealValues() {
        // The real reading took a phone number as an amount; the copy masked it.
        var real = List.of(section(List.of(row("2026-07-01", "CALL 9876543210", "9876543210.00", "DEBIT", "1.00")))); // synthetic-ok
        var copy = List.of(section(List.of(row("2026-07-01", "XXXX 9999999999", null, "DEBIT", "1.00"))));
        var result = BlankedCopyComparison.compare(real, copy);
        assertThat(result.matches()).isFalse();
        assertThat(result.toString()).doesNotContain("9876543210", "CALL"); // synthetic-ok
        assertThat(result.differences()).filteredOn(d -> d.field() == Field.DESCRIPTION)
                .allSatisfy(d -> assertThat(d.maskedTokens()).allMatch(t -> !t.contains("98765")));
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q test -Dtest=BlankedCopyComparisonTest`
Expected: compilation failure, `BlankedCopyComparison` not found.

- [ ] **Step 3: Implement**

```java
package com.finora.imports.blanked;

import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.VerificationFinding;
import com.finora.imports.pdf.trace.PdfTraceRedactor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Whether a blanked copy reads the same as the real statement it came from.
 *
 * <p>Compares only what reading produces. Not compared: the account holder's name and number
 * (masked in the copy by design), and the category, merchant and duplicate fields (they come from
 * description words and the customer's own data). A difference names a field and a row, never a
 * value: a real value can be text the copy hid, such as a phone number the real reading took for
 * an amount. The only text a difference carries is the copy's own masked tokens, which the copy
 * already contains.
 *
 * <p>A description is compared by masking the real one with the same rules as the copy, so the
 * two match exactly when the same words were grouped into that row in the same order.
 */
public final class BlankedCopyComparison {

    public enum Field { SECTION_COUNT, ROW_COUNT, UNPARSEABLE_COUNT, DATE, AMOUNT, DIRECTION, BALANCE,
        DESCRIPTION, PERIOD_START, PERIOD_END, OPENING_BALANCE, CLOSING_BALANCE, FINDINGS }

    public record Difference(int section, Integer row, Field field, List<String> maskedTokens) {}

    public record Result(List<Difference> differences) {
        public boolean matches() { return differences.isEmpty(); }
    }

    private BlankedCopyComparison() {}

    public static Result compare(List<StagedAccountSection> real, List<StagedAccountSection> copy) {
        List<Difference> out = new ArrayList<>();
        if (real.size() != copy.size()) out.add(new Difference(0, null, Field.SECTION_COUNT, List.of()));
        for (int s = 0; s < Math.min(real.size(), copy.size()); s++) {
            compareSection(s, real.get(s), copy.get(s), out);
        }
        return new Result(List.copyOf(out));
    }

    private static void compareSection(int s, StagedAccountSection real, StagedAccountSection copy, List<Difference> out) {
        DetectedAccountInfo a = real.detectedAccount();
        DetectedAccountInfo b = copy.detectedAccount();
        if (a != null || b != null) {
            if (a == null || b == null) {
                out.add(new Difference(s, null, Field.PERIOD_START, List.of()));
            } else {
                section(s, Field.PERIOD_START, a.statementPeriodStart(), b.statementPeriodStart(), out);
                section(s, Field.PERIOD_END, a.statementPeriodEnd(), b.statementPeriodEnd(), out);
                money(s, null, Field.OPENING_BALANCE, a.openingBalance(), b.openingBalance(), out);
                money(s, null, Field.CLOSING_BALANCE, a.closingBalance(), b.closingBalance(), out);
            }
        }
        if (size(real.unparseableRows()) != size(copy.unparseableRows())) {
            out.add(new Difference(s, null, Field.UNPARSEABLE_COUNT, List.of()));
        }
        if (!findingOutcomes(real).equals(findingOutcomes(copy))) {
            out.add(new Difference(s, null, Field.FINDINGS, List.of()));
        }
        List<StagedRow> ra = real.rows() == null ? List.of() : real.rows();
        List<StagedRow> rb = copy.rows() == null ? List.of() : copy.rows();
        if (ra.size() != rb.size()) out.add(new Difference(s, null, Field.ROW_COUNT, List.of()));
        for (int r = 0; r < Math.min(ra.size(), rb.size()); r++) {
            compareRow(s, r, ra.get(r), rb.get(r), out);
        }
    }

    private static void compareRow(int s, int r, StagedRow a, StagedRow b, List<Difference> out) {
        if (!Objects.equals(a.date(), b.date())) out.add(new Difference(s, r, Field.DATE, List.of()));
        money(s, r, Field.AMOUNT, a.amount(), b.amount(), out);
        if (!Objects.equals(a.type(), b.type())) out.add(new Difference(s, r, Field.DIRECTION, List.of()));
        money(s, r, Field.BALANCE, a.balanceAfter(), b.balanceAfter(), out);
        String maskedReal = a.description() == null ? null : PdfTraceRedactor.redactLine(a.description());
        if (!Objects.equals(maskedReal, b.description())) {
            out.add(new Difference(s, r, Field.DESCRIPTION, maskedTokens(b.description())));
        }
    }

    /** The copy's own tokens that were masked: they hold an X, x or 9 run the redactor wrote. */
    private static List<String> maskedTokens(String copyDescription) {
        if (copyDescription == null) return List.of();
        return Arrays.stream(copyDescription.split("\\s+"))
                .filter(t -> t.chars().anyMatch(Character::isLetterOrDigit))
                .filter(t -> t.chars().allMatch(c -> c == 'X' || c == 'x' || c == '9' || !Character.isLetterOrDigit(c)))
                .toList();
    }

    private static void section(int s, Field field, Object a, Object b, List<Difference> out) {
        if (!Objects.equals(a, b)) out.add(new Difference(s, null, field, List.of()));
    }

    private static void money(int s, Integer r, Field field, BigDecimal a, BigDecimal b, List<Difference> out) {
        boolean same = a == null ? b == null : b != null && a.compareTo(b) == 0;
        if (!same) out.add(new Difference(s, r, field, List.of()));
    }

    private static List<String> findingOutcomes(StagedAccountSection section) {
        if (section.verification() == null || section.verification().findings() == null) return List.of();
        return section.verification().findings().stream()
                .map(f -> f.rule() + "=" + f.outcome()).sorted().toList();
    }

    private static int size(List<?> list) { return list == null ? 0 : list.size(); }
}
```
`maskedTokens` keeps a token only when it has at least one letter or digit and every letter or
digit in it is `X`, `x` or `9`.

- [ ] **Step 4: Run them to verify they pass**

Run: `./mvnw -q test -Dtest=BlankedCopyComparisonTest`
Expected: 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/imports/blanked/BlankedCopyComparison.java backend/src/test/java/com/finora/imports/blanked/BlankedCopyComparisonTest.java
git commit -m "feat(imports): compare a blanked copy's reading with the real statement's, by field only"
```

### Task 4: The blanked-copy service

**Files:**
- Create: `backend/src/main/java/com/finora/imports/blanked/StaffStatementContent.java`
- Create: `backend/src/main/java/com/finora/imports/blanked/BlankedCopy.java`
- Create: `backend/src/main/java/com/finora/imports/blanked/BlankedCopyRefusedException.java`
- Create: `backend/src/main/java/com/finora/imports/blanked/BlankedCopyService.java`
- Create: `backend/src/main/java/com/finora/observability/BlankedCopyMetrics.java`
- Test: `backend/src/test/java/com/finora/imports/BlankedCopyServiceTest.java` (package
  `com.finora.imports`, for `PdfGeneratorTestSupport`)

**Interfaces:**
- Consumes: `PdfPreviewGenerator.acquire`, `generateSectionsFromAcquired` (Task 2);
  `BlankedCopyComparison` (Task 3); `PdfTraceRedactor`, `TraceFormat`, `TraceMetadata`,
  `TraceValidator.privacyKinds` (Task 1).
- Produces:
  - `record StaffStatementContent(String reference, String sourceFormat, byte[] content)`
  - `record BlankedCopy(String fileName, String trace, Check check, String notCheckedReason, List<BlankedCopyComparison.Difference> differences)` with `enum Check { MATCHES, DIFFERS, NOT_CHECKED }`
  - `class BlankedCopyRefusedException extends ApiException` with `List<String> kinds()`; HTTP 422,
    message "The blanked copy was refused: it still contained <kinds joined with ', '>."
  - `BlankedCopyService.make(StaffStatementContent) : BlankedCopy`
  - `BlankedCopyMetrics.produced(BlankedCopy.Check)`, `BlankedCopyMetrics.refused()`

- [ ] **Step 1: Write the failing tests**

```java
package com.finora.imports;

import com.finora.imports.blanked.*;
import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.PositionedText;
import com.finora.imports.pdf.TextSource;
import com.finora.imports.pdf.acquisition.AcquiredDocument;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.observability.BlankedCopyMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BlankedCopyServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-14T10:00:00Z"), ZoneOffset.UTC);

    private BlankedCopyService service(PdfPreviewGenerator generator) {
        return new BlankedCopyService(generator, new BlankedCopyMetrics(new SimpleMeterRegistry()), clock);
    }

    @Test
    void aSyntheticStatementMatches() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildReferenceNumberAndBalanceSample();
        BlankedCopy copy = service(PdfGeneratorTestSupport.generator())
                .make(new StaffStatementContent("HLD-2026-000001", "PDF", pdf));
        assertThat(copy.check()).isEqualTo(BlankedCopy.Check.MATCHES);
        assertThat(copy.fileName()).isEqualTo("blanked-HLD-2026-000001.trace");
        assertThat(copy.trace()).startsWith("# finora-pdf-trace");
    }

    @Test
    void theCopyCarriesNoCustomerTextAndNoFileName() throws Exception {
        // Build a sample whose narration carries a name, phone, email, long digit run; read
        // PdfFixtureBuilder for the row-builder API it uses internally and add a public
        // buildSampleWithPlantedCustomerData() there if no such sample exists.
        byte[] pdf = PdfFixtureBuilder.buildSampleWithPlantedCustomerData();
        BlankedCopy copy = service(PdfGeneratorTestSupport.generator())
                .make(new StaffStatementContent("HLD-2026-000002", "PDF", pdf));
        assertThat(copy.trace()).doesNotContain(PdfFixtureBuilder.PLANTED_NAME,
                PdfFixtureBuilder.PLANTED_PHONE, PdfFixtureBuilder.PLANTED_EMAIL,
                PdfFixtureBuilder.PLANTED_ACCOUNT_NUMBER);
        assertThat(copy.toString()).doesNotContain(PdfFixtureBuilder.PLANTED_NAME);
    }

    @Test
    void theReplayNeverUsesTheCustomersId() throws Exception {
        PdfPreviewGenerator real = PdfGeneratorTestSupport.generator();
        PdfPreviewGenerator spy = spy(real);
        byte[] pdf = PdfFixtureBuilder.buildReferenceNumberAndBalanceSample();
        service(spy).make(new StaffStatementContent("HLD-2026-000003", "PDF", pdf));
        var ids = org.mockito.ArgumentCaptor.forClass(UUID.class);
        verify(spy, times(2)).generateSectionsFromAcquired(ids.capture(), eq("statement.pdf"), any());
        assertThat(ids.getAllValues().get(0)).isEqualTo(ids.getAllValues().get(1));
    }

    @Test
    void aLayoutWithNoTableStillGetsACopy() throws Exception {
        PdfPreviewGenerator generator = spy(PdfGeneratorTestSupport.generator());
        doReturn(AcquiredDocument.of(List.of(new PositionedText("Xyz Bank letter to Asha", 10f, 10f, 0, 40f))))
                .when(generator).acquire(any(), any());
        BlankedCopy copy = service(generator).make(new StaffStatementContent("j-1", "PDF", new byte[]{1}));
        assertThat(copy.trace()).doesNotContain("Asha");
        assertThat(copy.check()).isIn(BlankedCopy.Check.MATCHES, BlankedCopy.Check.DIFFERS);
    }

    @Test
    void aDocumentWithNoTextGetsAnEmptyCopy() throws Exception {
        PdfPreviewGenerator generator = spy(PdfGeneratorTestSupport.generator());
        doReturn(AcquiredDocument.of(List.of())).when(generator).acquire(any(), any());
        BlankedCopy copy = service(generator).make(new StaffStatementContent("j-2", "PDF", new byte[]{1}));
        assertThat(copy.check()).isEqualTo(BlankedCopy.Check.MATCHES);
    }

    @Test
    void ocrRunsStillProduceACopyAndAVerdict() throws Exception {
        PdfPreviewGenerator generator = spy(PdfGeneratorTestSupport.generator());
        var ocr = new PositionedText("Date", 10f, 10f, 0, 20f, 8f, 0.9f, TextSource.OCR);
        doReturn(AcquiredDocument.of(List.of(ocr))).when(generator).acquire(any(), any());
        BlankedCopy copy = service(generator).make(new StaffStatementContent("j-3", "PDF", new byte[]{1}));
        assertThat(copy.check()).isIn(BlankedCopy.Check.MATCHES, BlankedCopy.Check.DIFFERS);
    }

    @Test
    void aRefusalNamesKindsNotValues() throws Exception {
        PdfPreviewGenerator generator = spy(PdfGeneratorTestSupport.generator());
        // An email survives redaction only if the redactor keeps it; plant one by stubbing the
        // acquirer with a run the redactor leaves unmasked in email shape. Read PdfTraceRedactor's
        // token rules first; if no input survives as an email, assert instead that every planted
        // email is masked and delete this test, recording why in a comment in the class.
        doReturn(AcquiredDocument.of(List.of(new PositionedText("statement@icici.bank", 0f, 0f, 0, 10f)))) // synthetic-ok
                .when(generator).acquire(any(), any());
        assertThatThrownBy(() -> service(generator).make(new StaffStatementContent("j-4", "PDF", new byte[]{1})))
                .isInstanceOf(BlankedCopyRefusedException.class)
                .hasMessageNotContaining("statement@");
    }

    @Test
    void aCsvIsRefused() {
        assertThatThrownBy(() -> service(PdfGeneratorTestSupport.generator())
                .make(new StaffStatementContent("j-5", "CSV", "a,b".getBytes())))
                .hasMessage("Blanked copy not available for CSV yet.");
    }

    @Test
    void aLockedPdfIsRefused() throws Exception {
        byte[] locked = PdfFixtureBuilder.encrypt(PdfFixtureBuilder.buildReferenceNumberAndBalanceSample(), "pw");
        assertThatThrownBy(() -> service(PdfGeneratorTestSupport.generator())
                .make(new StaffStatementContent("j-6", "PDF", locked)))
                .hasMessage("This statement is password-protected and no password is saved.");
    }

    @Test
    void aFailedReplayIsNotChecked() throws Exception {
        PdfPreviewGenerator generator = spy(PdfGeneratorTestSupport.generator());
        doThrow(new IllegalStateException("boom: Asha")).when(generator)
                .generateSectionsFromAcquired(any(), any(), any());
        BlankedCopy copy = service(generator).make(new StaffStatementContent("j-7", "PDF",
                PdfFixtureBuilder.buildReferenceNumberAndBalanceSample()));
        assertThat(copy.check()).isEqualTo(BlankedCopy.Check.NOT_CHECKED);
        assertThat(copy.notCheckedReason()).isEqualTo("The reading could not be repeated (IllegalStateException).");
    }
}
```
Add to `PdfFixtureBuilder`: public constants `PLANTED_NAME = "Asha Verma"`, `PLANTED_PHONE = "9876543210"`, (synthetic-ok)
`PLANTED_EMAIL = "asha.verma@example.org"`, `PLANTED_ACCOUNT_NUMBER = "100000000001"` and
`buildSampleWithPlantedCustomerData()`, built like `buildReferenceNumberAndBalanceSample()` with
those values in a header line and two narrations. The fixture-hygiene hook accepts
`100000000001` and `example.org`; if it blocks another planted value, change that value, not the
hook.

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q test -Dtest=BlankedCopyServiceTest`
Expected: compilation failure, `BlankedCopyService` not found.

- [ ] **Step 3: Implement the records, exception and metrics**

```java
package com.finora.imports.blanked;

/** A held statement's bytes, already unlocked, handed from a queue's service to the blanked-copy
 *  service and nowhere else. {@code reference} is the held item's id, never its file name. */
public record StaffStatementContent(String reference, String sourceFormat, byte[] content) {}
```
```java
package com.finora.imports.blanked;

import java.util.List;

public record BlankedCopy(String fileName, String trace, Check check, String notCheckedReason,
                          List<BlankedCopyComparison.Difference> differences) {
    public enum Check { MATCHES, DIFFERS, NOT_CHECKED }
}
```
```java
package com.finora.imports.blanked;

import com.finora.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;

/** The privacy scan found customer data in the copy. Carries the kinds only, never the data. */
public class BlankedCopyRefusedException extends ApiException {
    private final List<String> kinds;

    public BlankedCopyRefusedException(List<String> kinds) {
        super(HttpStatus.UNPROCESSABLE_ENTITY,
                "The blanked copy was refused: it still contained " + String.join(", ", kinds) + ".");
        this.kinds = List.copyOf(kinds);
    }

    public List<String> kinds() { return kinds; }
}
```
Check `ApiException`'s constructors first (`grep -n "public ApiException(" backend/src/main/java/com/finora/exception/ApiException.java`); use the `(HttpStatus, String)` one.

```java
package com.finora.observability;

import com.finora.imports.blanked.BlankedCopy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Counts only: how many blanked copies were made, by check result, and how many were refused. */
@Component
public class BlankedCopyMetrics {

    private final MeterRegistry registry;

    public BlankedCopyMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void produced(BlankedCopy.Check check) {
        Counter.builder("finora.blanked_copy.produced")
                .description("A blanked copy of a held statement was produced")
                .tag("check", check.name())
                .register(registry)
                .increment();
    }

    public void refused() {
        Counter.builder("finora.blanked_copy.refused")
                .description("A blanked copy was refused by the privacy scan")
                .register(registry)
                .increment();
    }
}
```

- [ ] **Step 4: Implement the service**

```java
package com.finora.imports.blanked;

import com.finora.exception.ApiException;
import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.PdfTextExtractor;
import com.finora.imports.pdf.PositionedText;
import com.finora.imports.pdf.acquisition.AcquiredDocument;
import com.finora.imports.pdf.trace.PdfTraceRedactor;
import com.finora.imports.pdf.trace.TraceFormat;
import com.finora.imports.pdf.trace.TraceMetadata;
import com.finora.imports.pdf.trace.TraceValidator;
import com.finora.observability.BlankedCopyMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Turns a held statement into a blanked copy staff may take away, and says whether that copy reads
 * the same as the statement. See docs/superpowers/specs/2026-10-14-fixing-statements-without-the-
 * real-statement-design.md, project 1.
 *
 * <p>The real bytes and runs never leave this method: nothing from them is logged, thrown or
 * returned. Only exception class names are ever logged.
 *
 * <p>The copy side of the check reads the trace FILE's own round trip, not the redacted runs in
 * memory: the file is what Claude gets, and it carries no run's source, height or confidence, so a
 * scanned statement can honestly come back DIFFERS.
 *
 * <p>Both readings use one fresh random user id, so no customer's rules, merchants, duplicates or
 * resolutions are read, and the two readings cannot differ because of them.
 */
@Service
public class BlankedCopyService {

    private static final Logger log = LoggerFactory.getLogger(BlankedCopyService.class);
    /** The file name both readings are given: never the customer's own. */
    static final String READING_NAME = "statement.pdf";

    private final PdfPreviewGenerator generator;
    private final BlankedCopyMetrics metrics;
    private final Clock clock;

    public BlankedCopyService(PdfPreviewGenerator generator, BlankedCopyMetrics metrics, Clock clock) {
        this.generator = generator;
        this.metrics = metrics;
        this.clock = clock;
    }

    public BlankedCopy make(StaffStatementContent content) {
        if (!"PDF".equalsIgnoreCase(content.sourceFormat())) {
            throw new ApiException(HttpStatus.CONFLICT, "Blanked copy not available for CSV yet.");
        }
        if (PdfTextExtractor.needsPassword(new ByteArrayInputStream(content.content()))) {
            throw new ApiException(HttpStatus.CONFLICT, "This statement is password-protected and no password is saved.");
        }
        AcquiredDocument real;
        try {
            real = generator.acquire(content.content(), null);
        } catch (Exception e) {
            log.warn("Blanked copy for {}: text acquisition failed ({})", content.reference(), e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.CONFLICT, "The statement's text could not be read.");
        }

        List<PositionedText> redacted = PdfTraceRedactor.redact(real.runs());
        List<String> kinds = TraceValidator.privacyKinds(redacted);
        if (!kinds.isEmpty()) {
            metrics.refused();
            throw new BlankedCopyRefusedException(kinds);
        }
        TraceMetadata metadata = new TraceMetadata(TraceMetadata.CURRENT_TRACE_VERSION,
                PdfTraceRedactor.REDACTOR_VERSION, PdfTraceRedactor.allowlistFingerprint(),
                LocalDate.now(clock).toString(), "held item " + content.reference(),
                List.of(), List.of(), "", List.of());
        String trace = TraceFormat.format(redacted, metadata);
        String fileName = "blanked-" + content.reference() + ".trace";

        BlankedCopy copy;
        try {
            UUID nobody = UUID.randomUUID();
            var realReading = generator.generateSectionsFromAcquired(nobody, READING_NAME, real).sections();
            var copyReading = generator.generateSectionsFromAcquired(nobody, READING_NAME,
                    AcquiredDocument.of(TraceFormat.parse(trace))).sections();
            BlankedCopyComparison.Result result = BlankedCopyComparison.compare(realReading, copyReading);
            copy = new BlankedCopy(fileName, trace,
                    result.matches() ? BlankedCopy.Check.MATCHES : BlankedCopy.Check.DIFFERS,
                    null, result.differences());
        } catch (Exception e) {
            log.warn("Blanked copy for {}: the check could not run ({})", content.reference(), e.getClass().getSimpleName());
            copy = new BlankedCopy(fileName, trace, BlankedCopy.Check.NOT_CHECKED,
                    "The reading could not be repeated (" + e.getClass().getSimpleName() + ").", List.of());
        }
        metrics.produced(copy.check());
        return copy;
    }
}
```
Check that a `Clock` bean exists (`grep -rn "Clock clock()" backend/src/main/java`); if none,
add `@Bean Clock clock() { return Clock.systemUTC(); }` to an existing `@Configuration` class that
already declares small beans, and say which in the commit message.

Check the `TraceMetadata` component order against its declaration and fix the constructor call if
it differs.

- [ ] **Step 5: Run the tests**

Run: `./mvnw -q test -Dtest='BlankedCopyServiceTest,BlankedCopyComparisonTest'`
Expected: all pass. If `aSyntheticStatementMatches` reports DIFFERS, do not loosen the comparison:
print the differences' fields and rows (no values), find which reading step depends on a masked
word, and report it before continuing.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/imports/blanked backend/src/main/java/com/finora/observability/BlankedCopyMetrics.java backend/src/test/java/com/finora/imports/BlankedCopyServiceTest.java backend/src/test/java/com/finora/imports/pdf/fixtures/PdfFixtureBuilder.java
git commit -m "feat(imports): make a blanked copy of a statement and check it reads the same"
```

### Task 5: Audited access to a held statement's bytes for a blanked copy

**Files:**
- Modify: `backend/src/main/java/com/finora/service/AdminHeldImportService.java` (beside `download`)
- Modify: `backend/src/main/java/com/finora/service/HeldStatementService.java` (beside `download`)
- Tests: in Task 6's ITs (these methods are exercised through the endpoints).

**Interfaces:**
- Consumes: `StaffStatementContent` (Task 4).
- Produces:
  - `AdminHeldImportService.contentForBlankedCopy(UUID actingAdminId, UUID jobId) : StaffStatementContent`
  - `HeldStatementService.contentForBlankedCopy(UUID actingAdminId, String heldId) : StaffStatementContent`

- [ ] **Step 1: Add the held-import method**

```java
/**
 * The held job's bytes for a blanked copy, unlocked in memory when a password is saved. Same
 * gates and audit-before-read as {@link #download}, under its own action, and without the role
 * pin: the copy that leaves the server is blanked, which is what a support role may receive.
 * The bytes go to BlankedCopyService and nowhere else.
 */
@Transactional
public StaffStatementContent contentForBlankedCopy(UUID actingAdminId, UUID jobId) {
    ImportJob job = require(jobId);
    requireHeld(job, "copied");
    boolean savedPassword = "PDF".equalsIgnoreCase(job.getSourceFormat())
            && statementPasswordService.hasJobPassword(job.getId());
    auditService.recordEvenOnRollback(actingAdminId, "HELD_IMPORT_BLANKED_COPY", "ImportJob", jobId,
            Map.of("actorId", actingAdminId.toString(),
                    "subjectUserId", job.getUserId().toString(),
                    "savedPasswordOnFile", savedPassword));
    byte[] content = statementContentService.read(job);
    if (savedPassword) {
        content = statementPasswordService.reviewCopy(job, content).content();
    }
    return new StaffStatementContent(job.getId().toString(), job.getSourceFormat(), content);
}
```

- [ ] **Step 2: Add the held-statement method**

```java
/** As {@link #download}, for a blanked copy: same gates and audit-before-read, its own action. */
@Transactional
public StaffStatementContent contentForBlankedCopy(UUID actingAdminId, String heldId) {
    HeldStatement held = require(heldId);
    refuseIfLockedWithoutPassword(held, "copied");
    ImportJob job = requireJob(held);
    boolean savedPassword = "PDF".equalsIgnoreCase(job.getSourceFormat())
            && statementPasswordService.hasJobPassword(job.getId());
    auditService.recordEvenOnRollback(actingAdminId, "HELD_STATEMENT_BLANKED_COPY", "HeldStatement",
            held.getId(), Map.of("actorId", actingAdminId.toString(),
                    "subjectUserId", held.getUserId().toString(),
                    "heldId", held.getHeldId(),
                    "savedPasswordOnFile", savedPassword));
    byte[] content = readStatement(held, job);
    if (savedPassword) {
        content = statementPasswordService.reviewCopy(job, content).content();
    }
    return new StaffStatementContent(held.getHeldId(), job.getSourceFormat(), content);
}
```
Read `require(heldId)` first: if it accepts decided holds, add the same open-hold check
`download`'s callers rely on, so a decided hold is refused with 409.

- [ ] **Step 3: Compile**

Run: `./mvnw -q compile`. Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/finora/service/AdminHeldImportService.java backend/src/main/java/com/finora/service/HeldStatementService.java
git commit -m "feat(imports): both held queues hand a statement to the blanked-copy service, audited first"
```

### Task 6: Endpoints, switch and integration tests

**Files:**
- Create: `backend/src/main/java/com/finora/controller/AdminBlankedCopyController.java`
- Create: `backend/src/main/java/com/finora/dto/BlankedCopyDto.java`
- Modify: `backend/src/main/resources/application.yml` (add `app.blanked-copy.enabled: ${APP_BLANKED_COPY_ENABLED:false}` under `app:`)
- Test: `backend/src/test/java/com/finora/controller/AdminBlankedCopyIT.java`
- Test: `backend/src/test/java/com/finora/controller/AdminBlankedCopyDisabledIT.java`

**Interfaces:**
- Consumes: Task 4 and Task 5 methods.
- Produces (HTTP):
  - `GET /api/v1/admin/blanked-copy/status` → `{ "enabled": boolean }`; any of the two permissions.
  - `GET /api/v1/admin/held-imports/{jobId}/blanked-copy` → `BlankedCopyDto.Response`; `IMPORT_TRIAGE_MANAGE`.
  - `GET /api/v1/admin/held-statements/{heldId}/blanked-copy` → `BlankedCopyDto.Response`; `TRUST_REVIEW_MANAGE`.
  - `BlankedCopyDto.Response(String fileName, String trace, String check, String notCheckedReason, List<Difference> differences)`, `BlankedCopyDto.Difference(int section, Integer row, String field, List<String> maskedTokens)`, `BlankedCopyDto.Status(boolean enabled)`.

- [ ] **Step 1: Write the failing ITs**

`AdminBlankedCopyIT` uses `@TestPropertySource(properties = {"app.blanked-copy.enabled=true",
"app.statement-storage.provider=filesystem", "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-blanked-copy-it"})`
and `@ExtendWith(OutputCaptureExtension.class)`. Copy `createUser`, `bearerFor` and `heldJob` from
`AdminHeldImportDownloadIT`, but store `PdfFixtureBuilder.buildSampleWithPlantedCustomerData()`
bytes and name the job's file `"Asha Verma statement.pdf"`. Copy `seedHold` from
`AdminHeldStatementDownloadIT` for the trust queue, storing the same bytes.

Add a support user helper:
```java
private User createSupportUser(String permission) {
    Permission p = permissionRepository.findAll().stream()
            .filter(x -> x.getName().equals(permission)).findFirst().orElseThrow();
    Role role = new Role();
    role.setName("SUPPORT_IT_" + UUID.randomUUID().toString().substring(0, 8));
    role.setDescription("Support role for the blanked-copy IT");
    role.getPermissions().add(p);
    roleRepository.save(role);
    User user = new User();
    user.setEmail("blanked-copy-it-" + UUID.randomUUID() + "@example.com");
    user.setPasswordHash("irrelevant-for-this-test");
    user.setFullName("Blanked Copy IT Support");
    user.setRole(role.getName());
    user.setAccountScope(User.SCOPE_ADMIN);
    user.setPhoneVerified(true);
    return userRepository.save(user);
}
```
Tests (each a `@Test` method):

```java
@Test
void aSupportAccountGetsTheBlankedCopyButNotTheStatement(CapturedOutput output) {
    ImportJob job = heldJob();
    User support = createSupportUser("IMPORT_TRIAGE_MANAGE");

    ResponseEntity<String> copy = get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy", support);
    ResponseEntity<byte[]> real = restTemplate.exchange("/api/v1/admin/held-imports/" + job.getId() + "/document",
            HttpMethod.GET, new HttpEntity<>(bearerFor(support)), byte[].class);

    assertThat(copy.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(real.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(copy.getBody()).contains("blanked-" + job.getId() + ".trace");
}

@Test
void nothingFromTheStatementOrItsFileNameLeaves(CapturedOutput output) {
    ImportJob job = heldJob();
    User admin = createUser("ADMIN");

    ResponseEntity<String> copy = get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy", admin);
    String audit = auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId()).toString();

    for (String planted : List.of(PdfFixtureBuilder.PLANTED_NAME, PdfFixtureBuilder.PLANTED_PHONE,
            PdfFixtureBuilder.PLANTED_EMAIL, PdfFixtureBuilder.PLANTED_ACCOUNT_NUMBER, "Asha Verma statement")) {
        assertThat(copy.getBody()).doesNotContain(planted);
        assertThat(audit).doesNotContain(planted);
        assertThat(output.getAll()).doesNotContain(planted);
    }
}

@Test
void everyCopyIsAuditedBeforeTheRead() {
    ImportJob job = heldJob();
    User admin = createUser("ADMIN");
    get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy", admin);
    assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId()))
            .anySatisfy(e -> assertThat(e.getAction()).isEqualTo("HELD_IMPORT_BLANKED_COPY"));
}

@Test
void aFailedReadIsStillAudited() {
    ImportJob job = heldJobWithMissingObject(); // store, then delete the object from `storage`'s root
    User admin = createUser("ADMIN");
    ResponseEntity<String> response = get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy", admin);
    assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
    assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId()))
            .anySatisfy(e -> assertThat(e.getAction()).isEqualTo("HELD_IMPORT_BLANKED_COPY"));
}

@Test
void aReleasedItemIsRefused() {
    ImportJob job = heldJob();
    job.returnToQueueForReprocess(Instant.now());
    importJobRepository.save(job);
    ResponseEntity<String> response = get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy", createUser("ADMIN"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
}

@Test
void theWrongQueuePermissionIsRefused() {
    ImportJob job = heldJob();
    User trustOnly = createSupportUser("TRUST_REVIEW_MANAGE");
    assertThat(get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy", trustOnly).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
}

@Test
void theTrustQueueWorksTheSameWay() {
    HeldStatement held = seedHold("HLD-2026-300001");
    User support = createSupportUser("TRUST_REVIEW_MANAGE");
    ResponseEntity<String> copy = get("/api/v1/admin/held-statements/HLD-2026-300001/blanked-copy", support);
    assertThat(copy.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(copy.getBody()).contains("blanked-HLD-2026-300001.trace").doesNotContain(PdfFixtureBuilder.PLANTED_NAME);
    assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(held.getId()))
            .anySatisfy(e -> assertThat(e.getAction()).isEqualTo("HELD_STATEMENT_BLANKED_COPY"));
}

@Test
void statusSaysEnabled() {
    assertThat(get("/api/v1/admin/blanked-copy/status", createSupportUser("IMPORT_TRIAGE_MANAGE")).getBody())
            .contains("\"enabled\":true");
}
```
`get(path, user)` is `restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class)`.
If `returnToQueueForReprocess` needs a different prior state, use whichever transition leaves the
job not held (read `ImportJob`), and keep the assertion.

`AdminBlankedCopyDisabledIT` (no `app.blanked-copy.enabled` property):
```java
@Test
void offMeansNotFoundAndStatusFalse() {
    ImportJob job = heldJob();
    User admin = createUser("ADMIN");
    assertThat(get("/api/v1/admin/held-imports/" + job.getId() + "/blanked-copy", admin).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get("/api/v1/admin/blanked-copy/status", admin).getBody()).contains("\"enabled\":false");
    assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId()))
            .noneSatisfy(e -> assertThat(e.getAction()).isEqualTo("HELD_IMPORT_BLANKED_COPY"));
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q verify -Dit.test='AdminBlankedCopyIT,AdminBlankedCopyDisabledIT' -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, 404 on every endpoint.

- [ ] **Step 3: Implement the DTO and controller**

```java
package com.finora.dto;

import com.finora.imports.blanked.BlankedCopy;
import java.util.List;

public final class BlankedCopyDto {
    private BlankedCopyDto() {}

    public record Status(boolean enabled) {}

    public record Difference(int section, Integer row, String field, List<String> maskedTokens) {}

    public record Response(String fileName, String trace, String check, String notCheckedReason,
                           List<Difference> differences) {
        public static Response of(BlankedCopy copy) {
            return new Response(copy.fileName(), copy.trace(), copy.check().name(), copy.notCheckedReason(),
                    copy.differences().stream()
                            .map(d -> new Difference(d.section(), d.row(), d.field().name(), d.maskedTokens()))
                            .toList());
        }
    }
}
```
```java
package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.BlankedCopyDto;
import com.finora.exception.ApiException;
import com.finora.imports.blanked.BlankedCopyService;
import com.finora.security.CurrentUser;
import com.finora.service.AdminHeldImportService;
import com.finora.service.HeldStatementService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Blanked copies of held statements: the one way statement-derived data leaves the server in
 * normal work. Gated by each queue's own permission, deliberately without the ADMIN role pin the
 * real-document endpoints carry -- the copy is blanked, which is what a support role may receive.
 * Behind {@code app.blanked-copy.enabled}: off, the copy endpoints do not exist.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminBlankedCopyController {

    private final AdminHeldImportService heldImports;
    private final HeldStatementService heldStatements;
    private final BlankedCopyService blankedCopies;
    private final CurrentUser currentUser;
    private final boolean enabled;

    public AdminBlankedCopyController(AdminHeldImportService heldImports, HeldStatementService heldStatements,
                                      BlankedCopyService blankedCopies, CurrentUser currentUser,
                                      @Value("${app.blanked-copy.enabled:false}") boolean enabled) {
        this.heldImports = heldImports;
        this.heldStatements = heldStatements;
        this.blankedCopies = blankedCopies;
        this.currentUser = currentUser;
        this.enabled = enabled;
    }

    @PreAuthorize("hasAnyAuthority('IMPORT_TRIAGE_MANAGE', 'TRUST_REVIEW_MANAGE')")
    @GetMapping("/blanked-copy/status")
    public ApiResponse<BlankedCopyDto.Status> status() {
        return ApiResponse.ok(new BlankedCopyDto.Status(enabled));
    }

    @PreAuthorize("hasAuthority('IMPORT_TRIAGE_MANAGE')")
    @GetMapping("/held-imports/{jobId}/blanked-copy")
    public ApiResponse<BlankedCopyDto.Response> heldImport(@PathVariable UUID jobId) {
        requireEnabled();
        return ApiResponse.ok(BlankedCopyDto.Response.of(
                blankedCopies.make(heldImports.contentForBlankedCopy(currentUser.id(), jobId))));
    }

    @PreAuthorize("hasAuthority('TRUST_REVIEW_MANAGE')")
    @GetMapping("/held-statements/{heldId}/blanked-copy")
    public ApiResponse<BlankedCopyDto.Response> heldStatement(@PathVariable String heldId) {
        requireEnabled();
        return ApiResponse.ok(BlankedCopyDto.Response.of(
                blankedCopies.make(heldStatements.contentForBlankedCopy(currentUser.id(), heldId))));
    }

    private void requireEnabled() {
        if (!enabled) throw new ApiException(HttpStatus.NOT_FOUND, "Not found");
    }
}
```
Confirm the import paths of `ApiResponse` and `CurrentUser` against `AdminHeldImportController`'s
imports and use the same ones.

- [ ] **Step 4: Run the ITs and the existing download ITs**

Run: `./mvnw -q verify -Dit.test='AdminBlankedCopyIT,AdminBlankedCopyDisabledIT,AdminHeldImportDownloadIT,AdminHeldStatementDownloadIT' -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all pass. If `nothingFromTheStatementOrItsFileNameLeaves` fails on `output.getAll()`,
find the log line (by its logger name only), and change that line so it no longer prints the text,
in the class that wrote it.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/controller/AdminBlankedCopyController.java backend/src/main/java/com/finora/dto/BlankedCopyDto.java backend/src/main/resources/application.yml backend/src/test/java/com/finora/controller/AdminBlankedCopyIT.java backend/src/test/java/com/finora/controller/AdminBlankedCopyDisabledIT.java
git commit -m "feat(admin): blanked copies of held statements, for anyone working the queue, behind a switch"
```

### Task 7: OpenAPI contract and admin portal

**Files:**
- Modify: `backend/openapi/openapi.json`, `admin-portal/src/api/generated-types.ts`,
  `frontend/src/api/generated-types.ts`, `mobile/src/api/generated-types.ts` (regenerated)
- Modify: `admin-portal/src/types/index.ts` (add `BlankedCopy`, `BlankedCopyDifference`)
- Modify: `admin-portal/src/api/endpoints.ts` (`adminBlankedCopyApi`)
- Create: `admin-portal/src/components/BlankedCopyPanel.tsx`
- Modify: `admin-portal/src/pages/HeldImports.tsx`, `admin-portal/src/pages/HeldStatementDetail.tsx`
- Test: `admin-portal/src/components/BlankedCopyPanel.test.tsx`

**Interfaces:**
- Consumes: the three endpoints from Task 6.
- Produces: `BlankedCopyPanel({ fetchCopy }: { fetchCopy: () => Promise<BlankedCopy> })`.

- [ ] **Step 1: Regenerate the contract**

Follow `docs/engineering/openapi-contracts.md` "Developer workflow": start local Postgres, run
`./mvnw -DskipTests package` and `./scripts/generate-openapi-spec.sh` in `backend/`, then
`npm run generate:types` under Node 22 in `admin-portal/`, `frontend/` and `mobile/`. Read the diff:
only the three new paths and the `BlankedCopyDto` schemas should be new.

- [ ] **Step 2: Add types and API functions**

`admin-portal/src/types/index.ts`:
```ts
export interface BlankedCopyDifference {
  section: number;
  row: number | null;
  field: string;
  maskedTokens: string[];
}

export interface BlankedCopy {
  fileName: string;
  trace: string;
  check: 'MATCHES' | 'DIFFERS' | 'NOT_CHECKED';
  notCheckedReason: string | null;
  differences: BlankedCopyDifference[];
}
```
`admin-portal/src/api/endpoints.ts`:
```ts
/** Blanked copies of held statements. Every fetch is audited server-side, like a download. */
export const adminBlankedCopyApi = {
  status: () => api.get<{ enabled: boolean }>('/admin/blanked-copy/status').then((r) => r.data),
  heldImport: (jobId: string) =>
    api.get<BlankedCopy>(`/admin/held-imports/${jobId}/blanked-copy`).then((r) => r.data),
  heldStatement: (heldId: string) =>
    api.get<BlankedCopy>(`/admin/held-statements/${heldId}/blanked-copy`).then((r) => r.data),
};
```

- [ ] **Step 3: Write the failing component tests**

```tsx
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import BlankedCopyPanel from './BlankedCopyPanel';
import * as download from '../lib/download';

const copy = (over = {}) => ({
  fileName: 'blanked-HLD-1.trace', trace: '# finora-pdf-trace v3\n', check: 'MATCHES' as const,
  notCheckedReason: null, differences: [], ...over,
});

describe('BlankedCopyPanel', () => {
  it('downloads the copy and says it matches', async () => {
    const spy = vi.spyOn(download, 'downloadBlob').mockImplementation(() => {});
    render(<BlankedCopyPanel fetchCopy={() => Promise.resolve(copy())} />);
    await userEvent.click(screen.getByRole('button', { name: 'Download blanked copy' }));
    await waitFor(() => expect(spy).toHaveBeenCalledWith(expect.any(Blob), 'blanked-HLD-1.trace'));
    expect(screen.getByText('Matches the real statement')).toBeInTheDocument();
  });

  it('lists differing rows and fields, with masked words only', async () => {
    vi.spyOn(download, 'downloadBlob').mockImplementation(() => {});
    render(<BlankedCopyPanel fetchCopy={() => Promise.resolve(copy({
      check: 'DIFFERS',
      differences: [{ section: 0, row: 12, field: 'DESCRIPTION', maskedTokens: ['Xxxxxxxxx'] },
                    { section: 0, row: null, field: 'ROW_COUNT', maskedTokens: [] }],
    }))} />);
    await userEvent.click(screen.getByRole('button', { name: 'Download blanked copy' }));
    expect(await screen.findByText('Does not match the real statement')).toBeInTheDocument();
    expect(screen.getByText('Row 13: description (hidden words: Xxxxxxxxx)')).toBeInTheDocument();
    expect(screen.getByText('Number of rows')).toBeInTheDocument();
  });

  it('says not checked with the reason', async () => {
    vi.spyOn(download, 'downloadBlob').mockImplementation(() => {});
    render(<BlankedCopyPanel fetchCopy={() => Promise.resolve(copy({
      check: 'NOT_CHECKED', notCheckedReason: 'The reading could not be repeated (IllegalStateException).',
    }))} />);
    await userEvent.click(screen.getByRole('button', { name: 'Download blanked copy' }));
    expect(await screen.findByText(/Not checked: The reading could not be repeated/)).toBeInTheDocument();
  });

  it('shows the server refusal message', async () => {
    render(<BlankedCopyPanel fetchCopy={() => Promise.reject({ response: { data: {
      message: 'The blanked copy was refused: it still contained phone number.' } } })} />);
    await userEvent.click(screen.getByRole('button', { name: 'Download blanked copy' }));
    expect(await screen.findByText('The blanked copy was refused: it still contained phone number.')).toBeInTheDocument();
  });
});
```
Match the error shape to how other admin pages read `err.response.data.message` (see
`HeldStatementDetail.tsx`'s download error handling) and use the same helper if one exists.

- [ ] **Step 4: Run them to verify they fail**

Run (Node 22, in `admin-portal/`): `npx vitest run src/components/BlankedCopyPanel.test.tsx`
Expected: FAIL, module not found.

- [ ] **Step 5: Implement the panel**

```tsx
import { useState } from 'react';
import { FileText } from 'lucide-react';
import { downloadBlob } from '../lib/download';
import type { BlankedCopy, BlankedCopyDifference } from '../types';

const FIELD_LABEL: Record<string, string> = {
  SECTION_COUNT: 'Number of accounts', ROW_COUNT: 'Number of rows', UNPARSEABLE_COUNT: 'Rows that could not be read',
  DATE: 'date', AMOUNT: 'amount', DIRECTION: 'money in or out', BALANCE: 'balance', DESCRIPTION: 'description',
  PERIOD_START: 'Statement start date', PERIOD_END: 'Statement end date',
  OPENING_BALANCE: 'Opening balance', CLOSING_BALANCE: 'Closing balance', FINDINGS: 'Check results',
};

function line(d: BlankedCopyDifference): string {
  const label = FIELD_LABEL[d.field] ?? d.field;
  if (d.row === null) return label;
  const hidden = d.maskedTokens.length ? ` (hidden words: ${d.maskedTokens.join(' ')})` : '';
  return `Row ${d.row + 1}: ${label}${hidden}`;
}

/**
 * Downloads a held statement's blanked copy and shows whether it reads the same as the real one.
 * Only field names, row numbers and words already hidden in the copy are shown -- never a value.
 */
export default function BlankedCopyPanel({ fetchCopy }: { fetchCopy: () => Promise<BlankedCopy> }) {
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<BlankedCopy | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function run() {
    setBusy(true);
    setError(null);
    try {
      const copy = await fetchCopy();
      downloadBlob(new Blob([copy.trace], { type: 'text/plain' }), copy.fileName);
      setResult(copy);
    } catch (err) {
      const message = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
      setError(message ?? 'The blanked copy could not be made.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-2">
      <button type="button" onClick={run} disabled={busy}
        className="inline-flex items-center gap-1.5 rounded-md border px-3 py-1.5 text-sm">
        <FileText className="h-3.5 w-3.5" />
        {busy ? 'Making blanked copy…' : 'Download blanked copy'}
      </button>
      {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
      {result?.check === 'MATCHES' && <p className="text-sm">Matches the real statement</p>}
      {result?.check === 'NOT_CHECKED' && <p className="text-sm">Not checked: {result.notCheckedReason}</p>}
      {result?.check === 'DIFFERS' && (
        <div className="text-sm">
          <p>Does not match the real statement</p>
          <ul aria-label="Differences" className="list-disc pl-5">
            {result.differences.map((d, i) => <li key={i}>{line(d)}</li>)}
          </ul>
        </div>
      )}
    </div>
  );
}
```
Before committing, replace the button's `className` with the exact `className` of the existing
"Download statement" button on `HeldStatementDetail.tsx`, so the panel matches the page.

- [ ] **Step 6: Mount it on both pages**

In `HeldImports.tsx` (inside the selected job's detail, beside the existing download controls) and
`HeldStatementDetail.tsx` (beside "Download statement"), render the panel when the status query
says enabled and the item is a PDF. Unlike the real-document button, it is not limited to
ADMIN/SUPER_ADMIN:

```tsx
const blankedStatus = useQuery({ queryKey: ['blanked-copy-status'], queryFn: adminBlankedCopyApi.status });
// ...
{blankedStatus.data?.enabled && detail.data?.sourceFormat === 'PDF' && (
  <BlankedCopyPanel fetchCopy={() => adminBlankedCopyApi.heldStatement(heldId)} />
)}
```
Use the field each page's detail type actually exposes for the format (read `HeldImportDetail` and
the held-statement detail type in `types/index.ts`); if the trust-review detail has no format, show
the panel for every item and rely on the server's CSV 409.

Add one test per page to the existing page test files: with the status mock returning
`{ enabled: true }` the "Download blanked copy" button is present for a non-admin role, and with
`{ enabled: false }` it is absent.

- [ ] **Step 7: Run admin checks**

Run (Node 22, in `admin-portal/`): `npm run lint`, `npm run build`, `npx vitest run`.
Expected: all clean; test count is the previous 767 plus the new tests.

- [ ] **Step 8: Commit**

```bash
git add backend/openapi/openapi.json admin-portal/src frontend/src/api/generated-types.ts mobile/src/api/generated-types.ts
git commit -m "feat(admin): a Download blanked copy button on held statements, with the check's result"
```

### Task 8: Verification, documents and PR

**Files:**
- Modify: `docs/superpowers/specs/2026-10-14-fixing-statements-without-the-real-statement-design.md`
  only if the build changed something the spec states.

- [ ] **Step 1: Full backend run**

Run in the background: `./mvnw verify` from `backend/`, output to the scratchpad. Expected: 0
failures, 0 errors. Do not commit while it runs.

- [ ] **Step 2: Corpus run unchanged**

Run `scripts/corpus-run.py` over the local statement collection before (on `origin/main`) and
after (on this branch); diff the outputs. Expected: byte-identical. Task 2 moved the reading body
and must change nothing.

- [ ] **Step 3: Match rate on the local real statements, counts only**

Write a scratchpad probe (outside the repo, compiled via `@argfile` as in the worktree probe
recipe) that, for every PDF in the local collection, runs `BlankedCopyService.make` with a stub
`StaffStatementContent` and prints one line per file: an opaque index, the check, the number of
differences per field name. No file name, no text. Expected: report the counts as they come. For
every DIFFERS, find which reading step depends on a masked word, by field and row, without
printing content, and record it in the PR as a limit or fix it with its own test.

- [ ] **Step 4: Browser check**

Start this worktree's admin portal against a stub API (the scratchpad `stub-api.mjs` pattern) that
returns a held item, `{ enabled: true }`, and one MATCHES, one DIFFERS and one refusal response.
Check both pages in light and dark, at 375 px wide, with a non-admin role: button present,
download fires, result shown, no console errors, no horizontal scroll. Screenshot.

- [ ] **Step 5: Push and open the PR**

```bash
git fetch origin
git log --oneline origin/main..HEAD
git push -u origin feature/statement-blanked-copy
gh pr create --title "feat(imports): staff take a blanked copy of a held statement, never the statement" --body-file <scratchpad>/pr-body.md
```
The PR body states what was measured (commands and counts), the match-rate result, the switch's
default, and that production stays off until the owner runs it. It ends with the "Generated with
Claude Code" line. The repository is public: CI skips integration tests on PRs, so the local
`verify` run is the integration evidence and the body says so.
