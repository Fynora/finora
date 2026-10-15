# Private Home for Test Copies Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. No sub-agents: the owner's standing rule.

**Goal:** Nothing derived from a statement is kept in the public repository. The 32 committed
traces, their golden outputs and every test that reads them move to the private repository
`Fynora/finora-statement-traces`, and run there against the public code. A trace taken from a
customer's held statement is deleted once that customer's import job no longer exists.

**Architecture:**
- The public backend gains a Maven profile, `private-traces`. Given the path of a checkout of the
  private repo, it adds that checkout's test sources and trace files to the test build. The trace
  loader and the golden snapshot test read their folders from system properties the profile sets.
  Without the profile, the public build compiles and passes with no traces at all.
- The private repo holds:
  - `traces/`, `golden/` and `tests/java/` (the moved test classes, in their original packages);
  - a script that checks out the public repo at a ref and runs the private tests against it;
  - workflows for the private repo's own pushes, a nightly run against public `main`, an on-demand
    run against any public branch, a privacy scan, and the nightly retention check.
- The retention check works like this:
  - It sends the references found in the traces' `generatedFrom: held item <ref>` lines to a new,
    narrow production endpoint.
  - The endpoint is authenticated by a dedicated key, and answers only which references no
    longer exist.
  - The check deletes those traces and their test classes in a commit to the private repo. Its
    history is not rewritten: owner's decision, 2026-10-15.

**Tech Stack:** Java 25, Maven (`build-helper-maven-plugin` in a profile), JUnit 5, Spring Boot
(one endpoint plus an IT), GitHub Actions, bash, gitleaks.

**Spec:** `docs/superpowers/specs/2026-10-14-fixing-statements-without-the-real-statement-design.md`
(project 3). The owner made these decisions on 2026-10-15, and they settle the spec's open points:
- The tests run in the private repo. No key to it ever goes in the public repo's CI.
- The 32 public traces move to private and are deleted from public `main`. Public history is not
  rewritten.
- Account deletion deletes the trace file only. The private repo's history is not rewritten.
- The private repo was created on 2026-10-15. Its only member is the owner (`siddharth705`).

## Global Constraints

**What the public repo may hold**
- After this work, public `main` holds no `*.trace` file and no `*.golden.txt` file anywhere.
- A check in the public repo's pre-commit hook and CI refuses either kind of file.
- Public `./mvnw verify` passes without the private repo present. No public test may fail or be
  skipped because a trace is missing: trace-backed tests leave the public tree entirely.

**Keys**
- No secret, token or key to the private repo is added to the public repo: not to its workflows,
  its secrets or its code.
- The private repo's workflows check out the public repo anonymously, since it's public.

**Code from public pull requests**
- The private workflows never run code from a fork. An on-demand run takes a branch name.
- That branch must exist in `Fynora/finora` itself, and its head commit must be authored by the
  owner. Otherwise the run stops before checking anything out.

**The retention endpoint**
- `POST /api/v1/internal/trace-retention/gone`.
- Off unless `APP_TRACE_RETENTION_KEY` is set. While off it answers 404.
- Auth: header `X-Trace-Retention-Key`, compared in constant time.
- Accepts at most 500 references, and returns only the subset that no longer exists.
- Its responses, logs and audit entries name no user, email or file.

**Repository rules**
- Commit messages carry no `Co-Authored-By` or any AI-attribution trailer, in both repositories.
- Work only in your own worktree of the public repo, and in a clone of the private repo under your
  scratchpad. Never write in `/Users/sid/Downloads/finora`.
- Never print trace contents in chat, logs or PR bodies. Counts and file names only.
- The owner creates every credential: the retention key, its Railway variable, and the private
  repo's secret. You never handle a key's value. You write where it goes and stop to ask him.
- Before deleting anything from the public repo, the moved copy must be committed and pushed to the
  private repo, and the private run must pass.
- Private repo Actions minutes count against the owner's 2,000-a-month allowance (public ones don't).
  Every scheduled private workflow runs at most once a day.

## Review Focus

1. **A public parser change that breaks a moved test.** Public CI can no longer see it. Expected:
   - the nightly private run fails, and that is visible: a red run and an issue in the private repo;
   - the documented local command catches it before merge.
   Test in Task 4 (run it once against a branch with a deliberately broken assertion and see it
   go red).
2. **A trace whose job still exists, and one whose job is gone.** Expected: only the second is
   deleted, along with its test class. A reference in either form (job UUID or `heldId`) works.
   Tests in Task 5 (`TraceRetentionIT`) and Task 6 (a dry run on a fixture list).
3. **The endpoint with no key, a wrong key, or 501 references.** Expected: 404 / 401 / 400, with
   nothing looked up. Test in Task 5.
4. **Someone re-adds a trace to the public repo.** Expected: the pre-commit hook refuses it, and if
   the hook is bypassed, CI fails. Test in Task 3.
5. **A test class that reads traces and also tests synthetic input.** Expected: it's split. The
   synthetic part stays public and the trace part moves. The total test count across both repos
   equals the count before the move. Checked in Task 2.

---

### Task 1: The public build can take tests and traces from a folder outside the repo

**Files:**
- Modify: `backend/pom.xml`: add profile `private-traces`
- Modify: `backend/src/test/java/com/finora/imports/pdf/fixtures/PdfTrace.java`: read the
  folder from `finora.traces.dir`
- Modify: `backend/src/test/java/com/finora/imports/pdf/GoldenOutputSnapshotTest.java`: read
  `GOLDEN_DIR` from `finora.golden.dir`
- Test: `backend/src/test/java/com/finora/imports/pdf/fixtures/PdfTraceLocationTest.java`

**Interfaces:**
- Produces:
  - `./mvnw test -Pprivate-traces -Dfinora.private.repo=<abs path>` adds:
    - `<path>/tests/java` as a test source root;
    - `<path>/traces` as a test resource dir, with `targetPath` `traces`;
    - system properties `finora.traces.dir=<path>/traces` and `finora.golden.dir=<path>/golden`.
  - `PdfTrace.tracesDir()`: `Path`, from the property `finora.traces.dir`, defaulting to
    `src/test/resources/traces`.

- [ ] **Step 1: Write the failing test**

```java
class PdfTraceLocationTest {

    @Test
    void tracesAreListedFromTheConfiguredFolder(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("b-synthetic.trace"), "# finora-pdf-trace v3\n");
        Files.writeString(dir.resolve("a-synthetic.trace"), "# finora-pdf-trace v3\n");
        Files.writeString(dir.resolve("notes.txt"), "x");
        String before = System.getProperty("finora.traces.dir");
        System.setProperty("finora.traces.dir", dir.toString());
        try {
            assertThat(PdfTrace.committedTraceNames()).containsExactly("a-synthetic", "b-synthetic");
            assertThat(PdfTrace.read("a-synthetic")).startsWith("# finora-pdf-trace v3");
        } finally {
            if (before == null) System.clearProperty("finora.traces.dir");
            else System.setProperty("finora.traces.dir", before);
        }
    }

    @Test
    void withNoFolderThereAreNoTraces(@TempDir Path dir) {
        String before = System.getProperty("finora.traces.dir");
        System.setProperty("finora.traces.dir", dir.resolve("absent").toString());
        try {
            assertThat(PdfTrace.committedTraceNames()).isEmpty();
        } finally {
            if (before == null) System.clearProperty("finora.traces.dir");
            else System.setProperty("finora.traces.dir", before);
        }
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw test -Dtest=PdfTraceLocationTest` (in `backend/`). Expected: FAIL, because
`committedTraceNames` still reads the fixed folder.

- [ ] **Step 3: Implement**

`PdfTrace`: replace the classpath read and the fixed folder with one folder:

```java
/** Where traces live: the private repo's {@code traces/} under the {@code private-traces} profile,
 *  {@code src/test/resources/traces} otherwise (empty in the public repo since project 3). */
public static Path tracesDir() {
    return Path.of(System.getProperty("finora.traces.dir", "src/test/resources/traces"));
}

public static String read(String traceName) {
    Path path = tracesDir().resolve(traceName + ".trace");
    try {
        return Files.readString(path, StandardCharsets.UTF_8);
    } catch (NoSuchFileException e) {
        throw new IllegalArgumentException("No such trace fixture: " + traceName);
    } catch (IOException e) {
        throw new UncheckedIOException("Could not read trace fixture " + traceName, e);
    }
}

public static List<String> committedTraceNames() {
    String[] files = tracesDir().toFile().list((d, name) -> name.endsWith(".trace"));
    if (files == null) return List.of();
    return Arrays.stream(files).map(n -> n.substring(0, n.length() - ".trace".length())).sorted().toList();
}
```

Grep the test tree for other readers of `/traces/` or `src/test/resources/traces`
(`grep -rn 'resources/traces\|"/traces/' backend/src/test/java`) and route each through
`PdfTrace.tracesDir()`. Do the same for `resources/golden`, using `finora.golden.dir` with default
`src/test/resources/golden`.

`backend/pom.xml`: add inside `<profiles>` (create the element if absent):

```xml
<profile>
  <id>private-traces</id>
  <build>
    <plugins>
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>build-helper-maven-plugin</artifactId>
        <version>3.6.0</version>
        <executions>
          <execution>
            <id>add-private-trace-tests</id>
            <phase>generate-test-sources</phase>
            <goals><goal>add-test-source</goal></goals>
            <configuration>
              <sources><source>${finora.private.repo}/tests/java</source></sources>
            </configuration>
          </execution>
        </executions>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
          <systemPropertyVariables>
            <finora.traces.dir>${finora.private.repo}/traces</finora.traces.dir>
            <finora.golden.dir>${finora.private.repo}/golden</finora.golden.dir>
          </systemPropertyVariables>
        </configuration>
      </plugin>
    </plugins>
  </build>
</profile>
```

Check the existing surefire configuration in the pom. If it already sets
`systemPropertyVariables`, the profile's must merge rather than replace: use
`combine.children="append"` and verify with `./mvnw help:effective-pom -Pprivate-traces -Dfinora.private.repo=/tmp/x`.

- [ ] **Step 4: Run it, and run the whole public suite with traces still in place**

Run: `./mvnw test -Dtest=PdfTraceLocationTest`, then `./mvnw test`. Expected: PASS, with the same
count as on `main` plus 2. The 32 traces are still in the default folder at this point.

- [ ] **Step 5: Commit**

```bash
git add backend/pom.xml backend/src/test/java
git commit -m "test(imports): trace and golden folders can come from outside the repo"
```

---

### Task 2: Move the traces, the golden outputs and the tests that read them

**Files:**
- Private repo (clone at `<scratchpad>/finora-statement-traces`):
  - Create: `README.md`, `traces/` (32 files), `golden/` (6 files), and
    `tests/java/com/finora/...` (moved classes, original packages)
- Public repo:
  - Delete: `backend/src/test/resources/traces/*.trace` and `backend/src/test/resources/golden/*`
  - Delete or split: every test class that calls `PdfTrace.load`, `PdfTrace.read`,
    `PdfTrace.committedTraceNames`, or reads the golden folder. List them with
    `grep -rln 'PdfTrace\.\(load\|read\|committedTraceNames\)\|finora.golden.dir' backend/src/test/java`.
    There were 27 classes on 2026-10-15, counting `PdfTrace.java` itself, which stays public.

**Interfaces:**
- Produces:
  - the private repo layout `traces/`, `golden/`, `tests/java/`;
  - the naming rule for split classes: the trace part of `FooTest` becomes `FooTracesTest` in the
    same package, in the private repo.

- [ ] **Step 1: Measure before moving**

Run in `backend/`: `./mvnw test`. Note the total count, and the per-class count for each class
in the grep list (from `target/surefire-reports/*.txt`). Save the numbers in the scratchpad.
They are the baseline for Step 5.

- [ ] **Step 2: Sort each class**

For each listed class (except `PdfTrace`, `PdfTraceLocationTest`, and fixtures the moved tests
compile against):
- every test method reads a trace: move the whole file;
- some methods do and some don't: move those methods into `FooTracesTest`, and keep the rest in
  `FooTest`. Copy the helpers the moved methods need. Don't make public helpers depend on
  private ones.
- shared helper classes that only moved tests use move with them; helpers that public tests also
  use stay public.

Write the sorting into a table in the scratchpad (class → moved / split / stays, with method
counts), for the PR body.

- [ ] **Step 3: Fill the private repo**

```bash
gh repo clone Fynora/finora-statement-traces <scratchpad>/finora-statement-traces
```

Copy the traces, the golden files and the moved or split classes in. Write `README.md`:

```markdown
# finora-statement-traces (private)

Blanked statement test copies ("traces") and the tests that read them, for Fynora's statement
reader. Never make this repository public, and never copy its files into Fynora/finora.

Run against the public code:

    ./scripts/run-against.sh main            # or a branch of Fynora/finora

Layout: `traces/` (one `.trace` per statement), `golden/` (expected reader output per trace),
`tests/java/` (JUnit classes in the backend's own packages, compiled with the backend's tests
under its `private-traces` Maven profile).

A trace made from a customer's held statement carries `# generatedFrom: held item <ref>`. The
nightly retention workflow deletes it, and its test class, once that import job no longer exists
(the customer deleted their account). Its test class must therefore be named after it and test
only it: `tests/java/com/finora/imports/pdf/traces/<TraceNameInPascalCase>TraceTest.java`.
```

Commit in the private repo as `test: move the statement traces and the tests that read them`.
Don't push yet.

- [ ] **Step 4: Run the private tests against this branch's code**

With the public files not yet deleted, the profile would load duplicate classes. So delete them
from the public worktree first, without committing:

```bash
cd backend
./mvnw test -Pprivate-traces -Dfinora.private.repo=<scratchpad>/finora-statement-traces
```

Expected: PASS. Moved classes run from the private folder, public ones from the worktree.

- [ ] **Step 5: Compare counts**

Run in `backend/`: `./mvnw test` without the profile. Expected: PASS, with `count == baseline −
moved methods`. Then, with the profile: `count == baseline + 2` (Task 1's tests). Each moved
class's count in the private run must equal its baseline count, or the split's two halves must add
up to it. Any mismatch is a lost or duplicated test: find it before going on.

- [ ] **Step 6: Push the private repo, then commit the public deletion**

```bash
git -C <scratchpad>/finora-statement-traces push origin main
git add -A backend/src/test
git commit -m "test(imports): statement traces and the tests that read them move to the private repository"
```

---

### Task 3: The public repo refuses traces from now on

**Files:**
- Modify: `scripts/check-fixture-hygiene.sh`. Its pre-commit step refuses any staged path ending
  `.trace` or `.golden.txt`.
- Modify: `scripts/test-fixture-hygiene.sh`, which tests the check.
- Create: `scripts/check-no-statement-traces.sh`. The CI form: fails if `git ls-files` lists any
  such path.
- Modify: `.github/workflows/ci.yml`. Add a step running the new script in the job that already
  runs the hygiene checks (search the workflow for `check-fixture-hygiene`).
- Modify: `scripts/trace-capture.sh`. It writes to `${FINORA_PRIVATE_TRACES:?set it to your
  finora-statement-traces checkout}/traces/` instead of `src/test/resources/traces/`.
- Delete: `.github/workflows/corpus-coverage-nightly.yml`. `CapabilityCorpusCoverageTest` moved;
  Task 4 runs it privately.
- Modify: `docs/engineering/` (create `private-statement-traces.md`). Cover:
  - where traces live and why;
  - the local command for any change under `backend/src/main/java/com/finora/imports/`;
  - the rule that a parser PR is not merge-ready until that command passes, or the on-demand
    private run passes for its branch;
  - the minutes cost.

  Link it from any doc that told people to add traces under `src/test/resources/traces`
  (`grep -rln 'resources/traces' docs`).

**Interfaces:** none consumed by later tasks.

- [ ] **Step 1: Write the failing hygiene test**

Add a case to `scripts/test-fixture-hygiene.sh` in the style of its existing cases:
1. Stage a file `backend/src/test/resources/traces/x.trace` in a temporary repo.
2. Expect the pre-commit check to exit non-zero and print
   `statement traces belong in Fynora/finora-statement-traces`.
3. Stage `golden/x.golden.txt` and expect the same.

- [ ] **Step 2: Run it to see it fail**

Run: `bash scripts/test-fixture-hygiene.sh`. Expected: the new cases fail.

- [ ] **Step 3: Implement both checks**

`scripts/check-no-statement-traces.sh`:

```bash
#!/usr/bin/env bash
# Statement traces and golden outputs live in the private Fynora/finora-statement-traces
# repository (project 3 of the 2026-10-14 design). Nothing derived from a statement may be committed here.
set -euo pipefail
found=$(git ls-files -- '*.trace' '*.golden.txt')
if [ -n "$found" ]; then
  echo "statement traces belong in Fynora/finora-statement-traces, not here:" >&2
  echo "$found" >&2
  exit 1
fi
```

Then add the matching staged-path check to `check-fixture-hygiene.sh`, using `git diff --cached
--name-only --diff-filter=ACR`.

- [ ] **Step 4: Run them**

Run: `bash scripts/test-fixture-hygiene.sh` and `bash scripts/check-no-statement-traces.sh`.
Expected: PASS. The second prints nothing, since Task 2 removed every trace. Run `shellcheck` on
both scripts if the repo's CI runs it.

- [ ] **Step 5: Commit**

```bash
git add scripts .github/workflows docs
git commit -m "ci: refuse statement traces in the public repository and point capture at the private one"
```

---

### Task 4: The private repo runs its tests

**Files (private repo):**
- Create: `scripts/run-against.sh`
- Create: `.github/workflows/traces.yml`
- Create: `.github/workflows/privacy-scan.yml`

**Interfaces:**
- Consumes the Task 1 profile.
- Produces `scripts/run-against.sh <ref>`, which exits 0 only when every private test passes
  against `Fynora/finora` at `<ref>`.

- [ ] **Step 1: Write the runner**

```bash
#!/usr/bin/env bash
# Runs this repository's tests against Fynora/finora at a branch or commit.
# Usage: scripts/run-against.sh <ref>
set -euo pipefail
ref="${1:?usage: run-against.sh <branch-or-sha>}"
here="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
git clone --quiet --filter=blob:none https://github.com/Fynora/finora.git "$work/finora"
git -C "$work/finora" checkout --quiet "$ref"
cd "$work/finora/backend"
./mvnw -q -B test -Pprivate-traces -Dfinora.private.repo="$here" \
  -Dtest='com.finora.imports.**.*Trace*Test,com.finora.imports.**.*Corpus*Test,com.finora.imports.**.*Regression*Test,com.finora.imports.**.*Golden*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

The `-Dtest` pattern must select exactly the moved classes. After writing it, compare the number
of classes surefire reports with the number of files under `tests/java`. If they differ, replace
the pattern with a list generated from `tests/java`. `-Dtest` overrides surefire's includes
(see the repo's own note on this), so check by counting, not by trust.

Run `scripts/run-against.sh <your public branch>` locally. Expected: PASS, with the class count
equal to the files under `tests/java`.

- [ ] **Step 2: Write the workflow**

`.github/workflows/traces.yml`:

```yaml
name: Traces against public code
on:
  push:
    branches: [main]
  pull_request:
  schedule:
    - cron: '30 4 * * *'   # once a day: private minutes are billed
  workflow_dispatch:
    inputs:
      ref:
        description: 'Branch of Fynora/finora to test (never a fork)'
        required: true
        default: main
permissions:
  contents: read
  issues: write
jobs:
  run:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '25' }
      - name: Refuse anything but an owner-authored branch of Fynora/finora
        if: github.event_name == 'workflow_dispatch'
        env:
          REF: ${{ inputs.ref }}
          GH_TOKEN: ${{ github.token }}
        run: |
          case "$REF" in *[!A-Za-z0-9._/-]*) echo "bad ref"; exit 1;; esac
          author=$(curl -fsS "https://api.github.com/repos/Fynora/finora/commits/$REF" | jq -r '.author.login')
          [ "$author" = "siddharth705" ] || { echo "head of $REF is not authored by the owner"; exit 1; }
      - name: Run
        env:
          REF: ${{ github.event_name == 'workflow_dispatch' && inputs.ref || 'main' }}
        run: scripts/run-against.sh "$REF"
      - name: Open an issue when the nightly run fails
        if: failure() && github.event_name == 'schedule'
        env: { GH_TOKEN: '${{ github.token }}' }
        run: gh issue create --title "Nightly trace run failed $(date -u +%F)" --body "See ${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}"
```

Pin the action versions to the same majors the public repo's workflows use, and the Java version
to the public backend's (`grep -rn java-version .github/workflows` in the public repo).

The public API call is anonymous: the public repo needs no token.

- [ ] **Step 3: The privacy scan**

`.github/workflows/privacy-scan.yml` runs on `push` and `pull_request`:
1. gitleaks, configured the way the public repo's `gitleaks-nightly.yml` is.
2. `scripts/run-against.sh main` restricted to `TraceCorpusHealthTest`. That runs the
   `TraceValidator` checks (email, Indian mobile, full IFSC, non-ASCII) over every trace.

GitHub's own secret scanning needs a paid plan on private repos, so these two stand in for it.
Say so in the README.

- [ ] **Step 4: See both go green, then see a red one**

Push the private repo and watch both workflows pass (`gh run list -R Fynora/finora-statement-traces`).
Then:
1. Push a throwaway public branch that breaks one assertion in a moved test's subject code.
2. Dispatch `traces.yml` with that ref and see it fail.
3. Delete the branch.

Record the run URLs for the PR body.

- [ ] **Step 5: Commit** (private repo)

```bash
git -C <scratchpad>/finora-statement-traces add scripts .github README.md
git -C <scratchpad>/finora-statement-traces commit -m "ci: run the traces against the public code, daily and on demand, with a privacy scan"
git -C <scratchpad>/finora-statement-traces push origin main
```

---

### Task 5: The retention endpoint (public backend)

**Files:**
- Create: `backend/src/main/java/com/finora/controller/TraceRetentionController.java`
- Create: `backend/src/main/java/com/finora/imports/blanked/TraceRetentionService.java`
- Modify: the security config, so the path is permitted without a user session (the controller
  checks the key). Find where other machine-to-machine paths are permitted, for example the
  Razorpay webhook (`grep -rn 'webhook' backend/src/main/java/com/finora/config`).
- Modify: `backend/src/main/java/com/finora/config/ProductionConfigValidator.java`, only if it
  lists optional keys. The key is optional: unset means off.
- Test: `backend/src/test/java/com/finora/controller/TraceRetentionIT.java`

**Interfaces:**
- Produces HTTP `POST /api/v1/internal/trace-retention/gone`:
  - header `X-Trace-Retention-Key`;
  - body `{ "references": string[] }`;
  - answer `200 { "gone": string[] }`.

- [ ] **Step 1: Write the failing IT**

```java
@TestPropertySource(properties = "app.trace-retention.key=test-retention-key-for-it-only")
class TraceRetentionIT extends AbstractIntegrationTest {

    @Test void aMissingKeyIsUnauthorized()          // no header -> 401, body names nothing
    @Test void aWrongKeyIsUnauthorized()            // wrong header -> 401
    @Test void moreThan500ReferencesAreRefused()    // 501 -> 400
    @Test void onlyReferencesWhoseJobIsGoneComeBack() {
        // existing job (UUID ref), deleted job (UUID of a job saved then deleted), unknown UUID,
        // existing heldId, heldId whose hold row is gone, and "not-a-reference"
        // -> gone == [deleted job, unknown UUID, gone heldId]; "not-a-reference" is neither listed nor an error
    }
}

@TestPropertySource(properties = "app.trace-retention.key=")
class TraceRetentionOffIT extends AbstractIntegrationTest {
    @Test void withNoKeyConfiguredTheEndpointDoesNotExist()   // 404 even with a header
}
```

Write each body out fully, with the request built like the other ITs' `restTemplate.exchange`
calls. A malformed reference is not "gone": deleting a trace on a typo would lose a test for
nothing.

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw verify -Dit.test='TraceRetention*IT' -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false`.

- [ ] **Step 3: Implement**

```java
/**
 * Tells the private trace repository's nightly job which held-item references no longer exist, so
 * it can delete traces made from a customer who has since deleted their account. Machine-to-machine:
 * a dedicated key, off (404) unless configured, and an answer that is a subset of the question --
 * no user, email or file name is ever returned or logged.
 */
@RestController
@RequestMapping("/api/v1/internal/trace-retention")
public class TraceRetentionController {

    private final TraceRetentionService service;
    private final byte[] key;   // empty = off

    public TraceRetentionController(TraceRetentionService service,
                                    @Value("${app.trace-retention.key:}") String key) {
        this.service = service;
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    public record Request(List<String> references) {}
    public record Response(List<String> gone) {}

    @PostMapping("/gone")
    public ResponseEntity<Response> gone(@RequestHeader(value = "X-Trace-Retention-Key", required = false) String given,
                                         @RequestBody Request body) {
        if (key.length == 0) return ResponseEntity.notFound().build();
        if (given == null || !MessageDigest.isEqual(key, given.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<String> refs = body == null || body.references() == null ? List.of() : body.references();
        if (refs.size() > 500) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(new Response(service.gone(refs)));
    }
}
```

`TraceRetentionService.gone(List<String>)`, `@Transactional(readOnly = true)`, decides each
reference like this:
- parses as a UUID: gone when `importJobRepository.existsById` is false;
- matches the `heldId` format (read `HeldStatement`'s id generator for the exact pattern): gone
  when `heldStatementRepository.findByHeldId` is empty, or its job no longer exists;
- anything else: skipped.

Log one line with the counts only: `trace retention: {} asked, {} gone`.

Make sure the endpoint sits outside the user-session filters: phone verification, activity
interceptor and CSRF. Do it the way the webhook path does, and prove it in the IT (a call with no
`Authorization` header and the right key returns 200).

- [ ] **Step 4: Run them and the full backend suite**

Run: `./mvnw verify`. Expected: 0 failures. Record the counts.

- [ ] **Step 5: Commit**

```bash
git add -A backend/src
git commit -m "feat(imports): a keyed endpoint tells the private trace repo which held items are gone"
```

- [ ] **Step 6: Stop and ask the owner for the key**

The owner:
1. generates a random key of 32+ characters;
2. sets it as `APP_TRACE_RETENTION_KEY` on the production backend in Railway, after this PR is
   merged and deployed;
3. sets it as the secret `TRACE_RETENTION_KEY` in `Fynora/finora-statement-traces`;
4. sets `TRACE_RETENTION_URL` there to the production API base URL.

Send him those four steps as a short message, and wait. Don't create, see or paste the key
yourself.

---

### Task 6: The private repo deletes traces whose customer is gone

**Files (private repo):**
- Create: `scripts/retention.sh`
- Create: `scripts/test-retention.sh`
- Create: `.github/workflows/retention.yml`

**Interfaces:**
- Consumes the Task 5 endpoint and the README's naming rule:
  `traces/<name>.trace` ↔ `tests/java/com/finora/imports/pdf/traces/<Pascal(name)>TraceTest.java`.

- [ ] **Step 1: Write the failing script test**

`scripts/test-retention.sh` builds a temporary folder with:
- `traces/legacy.trace`, with no `generatedFrom: held item` line;
- `traces/blanked-<uuidA>.trace`, with `# generatedFrom: held item <uuidA>`, and its test class;
- `traces/blanked-<uuidB>.trace`, with `# generatedFrom: held item <uuidB>`, and its test class.

It points `scripts/retention.sh --dry-run` at a stub answer file that lists `<uuidB>` as gone.
Expected:
- the script prints `would delete: traces/blanked-<uuidB>.trace` and its test class path, and
  nothing else;
- without `--dry-run`, both files are gone and the other two traces remain.

- [ ] **Step 2: Run it to see it fail**

Run: `bash scripts/test-retention.sh`.

- [ ] **Step 3: Implement**

`scripts/retention.sh [--dry-run] [--answer-file F]`:
1. Collect each `traces/*.trace` whose header has `# generatedFrom: held item <ref>`, as
   `ref → file` (use `grep -m1` on the header block only).
2. With no references, exit 0.
3. Otherwise POST them in chunks of 500 to `$TRACE_RETENTION_URL/api/v1/internal/trace-retention/gone`
   with header `X-Trace-Retention-Key: $TRACE_RETENTION_KEY`, or read `--answer-file` in tests.
   Any HTTP failure: exit 1 and delete nothing.
4. For each gone reference, delete its trace and its test class. A missing test class is not an
   error. A test class still referencing the trace by name elsewhere (`grep -rl`) is an error:
   print the path, delete nothing, exit 1.
5. Print only file paths and counts. Never trace contents.

`.github/workflows/retention.yml`:
- trigger: `schedule` at `0 5 * * *`, plus `workflow_dispatch`;
- `permissions: contents: write`;
- steps: checkout, then `scripts/retention.sh`;
- if files changed, commit as `chore: delete traces whose import job is gone` and push to `main`.
  That push triggers `traces.yml`, which proves the remaining tests still pass.
- If the secrets are unset, the job prints "retention not configured" and exits 0. It must not
  fail every night before the owner has set them.

- [ ] **Step 4: Run them**

Run: `bash scripts/test-retention.sh`. Expected: PASS. After the owner has set the secrets
(Task 5, Step 6), dispatch the workflow once. Expected: "0 gone": the 32 moved traces carry no
held-item reference, and no customer trace exists yet.

- [ ] **Step 5: Commit** (private repo)

```bash
git -C <scratchpad>/finora-statement-traces add scripts .github
git -C <scratchpad>/finora-statement-traces commit -m "ci: delete a trace and its test once its import job is gone"
git -C <scratchpad>/finora-statement-traces push origin main
```

---

### Task 7: Verification and PR

- [ ] **Step 1: Public full runs on the final code**
  - Public `./mvnw verify`.
  - `bash scripts/test-fixture-hygiene.sh` and `bash scripts/check-no-statement-traces.sh`.
  - `git ls-files '*.trace' '*.golden.txt'`, which must print nothing.

- [ ] **Step 2: Private runs**

Run `scripts/run-against.sh <your branch>`, and check the `traces.yml` and `privacy-scan.yml`
runs on the private repo's `main` are green. The total test count across both repos must equal the
Task 2 baseline plus the tests added since.

- [ ] **Step 3: Check against the spec**

Name the proof for each point in project 3 of the spec:
- who has access: the owner only (`gh api repos/Fynora/finora-statement-traces/collaborators`);
- how the tests run: Task 4;
- customer deletion: Tasks 5 and 6;
- secret scanning: Task 4 Step 3;
- the 32 traces: Task 2.

Say in the PR that the org's default repository permission is `read`, so any future org member
could read the private repo. Changing it to `none` before adding support staff is the owner's
settings change.

- [ ] **Step 4: Push and open the public PR**

Before pushing, run `git fetch origin && git log --oneline origin/main..HEAD`. The PR body covers:
- what moved, with counts per class, from the Task 2 table;
- the new local command for parser changes;
- the minutes cost of the private workflows;
- the four steps the owner must do for the key;
- what was verified.

Don't merge it. It should be merged only after the private repo's `main` holds the moved files
and its runs are green: say this at the top of the PR.

- [ ] **Step 5: After the owner merges**

Run `gh pr view <n> --json state,mergedAt,headRefOid` and compare it with your last push. Then
dispatch `traces.yml` with `ref=main` and report the result.
