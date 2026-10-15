# Safer Access to the Real Statement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. The owner's standing rule is no sub-agents unless he asks for them, so this plan is written for one implementer working through it in order.

**Goal:** An Admin or Super Admin can look at a held statement's real pages in the admin portal,
never download them. Every opening needs a typed reason, emails the Super Admins, is audited, and
tells the customer the date and that it was done to fix a reading problem.

**Architecture:** The two `/document` download endpoints and their service methods are removed. A
new `com.finora.imports.original` package holds a renderer and a view service:
- The renderer turns PDF pages into PNG images on the server; CSV becomes plain text lines.
- The view service opens a short-lived view row (30 minutes, one viewer), then serves page images
  for that view only.

Each held queue's service gains:
- an audited "content for an original view" method, written before any bytes are read;
- an unaudited "content for an open view" method for page fetches, which re-checks that the item
  is still held.

After the opening commits, the Super Admins get an email and the customer gets a push and an email.
The customer's web import detail page lists the dates the statement was opened.

**Tech Stack:**
- Backend: Java 25, Spring Boot, PDFBox (`PDFRenderer`, already used by `TesseractEngine`), Flyway.
- Backend tests: JUnit 5, AssertJ, Mockito, Testcontainers ITs (`AbstractIntegrationTest`).
- Admin portal and web frontend: React + TypeScript, TanStack Query, Vitest, Testing Library.
- Node 22 for both (`npx node@22`).

**Spec:** `docs/superpowers/specs/2026-10-14-fixing-statements-without-the-real-statement-design.md`
(project 2, plus "Decisions", "Before and after" and "Risks that remain" item 4).

## Global Constraints

**Who may open a statement, and how**
- Only `ADMIN` and `SUPER_ADMIN`, plus the queue permission, restated in one `@PreAuthorize`
  expression per endpoint:
  - held imports: `hasAuthority('IMPORT_TRIAGE_MANAGE') and hasAnyRole('ADMIN', 'SUPER_ADMIN')`;
  - held statements: `hasAuthority('TRUST_REVIEW_MANAGE') and hasAnyRole('ADMIN', 'SUPER_ADMIN')`.
  Never split it into class-level plus method-level rules: a method-level rule replaces the class
  rule, it doesn't add to it.
- Page and CSV fetches need `hasAnyRole('ADMIN', 'SUPER_ADMIN')`, and the view must belong to the
  caller and be unexpired, or the answer is 404. The view itself was opened under the queue's rule.
- Only held items: `HELD_FOR_REVIEW` import jobs, and open trust-review holds. Every page fetch
  checks again, and an item released since the view opened is a 409.
- No endpoint returns the original file's bytes. A PDF goes out as PNG page images only, a CSV as
  text lines in JSON. No response carries `Content-Disposition: attachment`. Every response sets
  `Cache-Control: no-store`.

**The reason**
- A reason is required: trimmed, 15 to 500 characters. Otherwise 400, and no audit entry, no read,
  no email and no notice.

**Audit**
- Actions: `HELD_IMPORT_ORIGINAL_VIEWED` (entity `ImportJob`) and `TRUST_REVIEW_ORIGINAL_VIEWED`
  (entity `HeldStatement`).
- Written with `recordEvenOnRollback` before any bytes are read.
- Details: `actorId`, `subjectUserId`, `reason`, `savedPasswordOnFile`, and `heldId` on the trust
  queue. Nothing from the statement.

**The Super Admin email**
- Sent to every active user holding the `SUPER_ADMIN` role, after the opening commits.
- Contents, HTML-escaped: who opened it (their email), when, which item (a link to it in the admin
  portal) and the reason.
- No customer name, customer email, file name, bank figures or statement text.

**The customer notice**
- Written when a view opens successfully (bytes read and the page count known). Never on a refused
  or failed attempt.
- New `NotificationType.IMPORT_STATEMENT_OPENED_FOR_FIX`, sent as `PUSH` and `EMAIL`, with
  templates seeded by migration. Its email uses the support sender.
- At most one per job per day (in India time): dedupe key
  `"IMPORT_OPENED_FOR_FIX_" + jobId + "_" + yyyy-MM-dd`.
- Wording, exactly:
  - email title: `We opened your statement to fix a reading problem`
  - email body: `On {{date}}, a member of our team opened the statement you uploaded so we could fix a problem reading it. Only administrators can do this, and every opening is recorded. Nothing in your accounts was changed by it.`
  - push title: `Your statement was opened`
  - push body: `On {{date}} our team opened it to fix a reading problem.`
  - web import detail line: `Opened by our team on {date} to fix a reading problem.`
- `{{date}}` / `{date}` looks like `15 Oct 2026`: pattern `d MMM yyyy`, `Locale.ENGLISH`, zone
  `UserZone.DEFAULT`.
- Nothing about who opened it.

**Where it reaches**
- Mobile gets the push and the email only. No mobile screen change, so no app release.
- A mobile import-history line waits for a later app release, and is listed in the PR as deferred.

**Removed**
- `GET /api/v1/admin/held-imports/{jobId}/document` and
  `GET /api/v1/admin/held-statements/{heldId}/document`.
- `AdminHeldImportService.download` and `HeldStatementService.download`.
- The portal's `adminHeldImportApi.download` and `adminHeldStatementApi.download`.
- Past audit rows with `HELD_IMPORT_DOWNLOADED` / `TRUST_REVIEW_DOCUMENT_DOWNLOADED` stay as
  they are.

**Repository rules**
- Commit messages carry no `Co-Authored-By` or any AI-attribution trailer.
- Work only in your own worktree, branched fresh from `origin/main`. Never write in
  `/Users/sid/Downloads/finora`.
- Flyway: before adding a migration, run `git fetch origin` and list
  `backend/src/main/resources/db/migration` on `origin/main`. At the time of writing the highest is
  `V270`, so this plan uses `V271` and `V272`. If those are taken, use the next free numbers.
- Never print a real statement's content. Tests build their own PDFs in code with PDFBox.
- Tests that plant customer-shaped values mark each such line `// synthetic-ok`.
- `./mvnw test` skips ITs; the full run is `./mvnw verify`. Never commit while `verify` is running.

## Review Focus

1. **An item released while a viewer has it open.** Someone approves or resolves the item while
   another admin's view is still on screen. Expected: the next page fetch is a 409 and nothing more
   is served. Test in Task 6 (`aPageOfAReleasedItemIsRefused`).
2. **Someone else's view id.** A second admin, even a Super Admin, uses another admin's `viewId`.
   Expected: 404, not 403, so the id is not confirmed. Test in Task 4
   (`anotherViewersIdIsNotFound`) and Task 6.
3. **A locked PDF.**
   - Trust queue with no saved password: 409 before the audit entry. No notice, no view row.
   - Import queue whose saved password fails: 409, and the audit entry stays (the attempt is the
     event). No notice, no view row.
   Tests in Task 3 (`aLockedTrustHoldIsRefusedBeforeTheAudit`,
   `aFailedUnlockStillLeavesTheAuditButNoView`) and Task 4 (`noNoticeWhenTheReadFails`).
4. **The same admin opens the same item twice in a day.** Expected: two view rows, two audit
   entries and two Super Admin emails, but one customer notification. Test in Task 5
   (`twoOpeningsOnOneDaySendOneCustomerNotice`).
5. **A reason with HTML in it, or only spaces.**
   - HTML: escaped in the Super Admin email (Task 5, `theReasonIsEscapedInTheEmail`).
   - Only spaces: a 400 with no audit entry (Task 6, `aBlankReasonIsRefusedBeforeAnything`).

---

### Task 1: The view record

**Files:**
- Create: `backend/src/main/resources/db/migration/V271__statement_original_views.sql`
- Create: `backend/src/main/java/com/finora/imports/original/StatementOriginalView.java`
- Create: `backend/src/main/java/com/finora/imports/original/StatementOriginalViewRepository.java`
- Test: `backend/src/test/java/com/finora/imports/original/StatementOriginalViewRepositoryIT.java`

**Interfaces:**
- Produces:
  - `StatementOriginalView.open(UUID jobId, UUID subjectUserId, UUID viewerId, String queue, String itemRef, String format, int pageCount, String reason, Instant now)`
  - getters `getId()`, `getJobId()`, `getSubjectUserId()`, `getViewerId()`, `getQueue()`,
    `getItemRef()`, `getFormat()`, `getPageCount()`, `getOpenedAt()`, `getExpiresAt()`
  - `boolean isLiveFor(UUID viewerId, Instant now)`
  - `StatementOriginalViewRepository.findByJobIdOrderByOpenedAtAsc(UUID jobId)`

- [ ] **Step 1: Write the migration**

```sql
-- One row per time an Admin or Super Admin opened a held statement's real pages in the admin
-- portal (project 2 of docs/superpowers/specs/2026-10-14-fixing-statements-without-the-real-statement-design.md).
-- The row is what page fetches are checked against (one viewer, 30 minutes), and the opened_at
-- dates are what the customer is shown on their import detail page. The reason is kept here and
-- on the audit entry; this table's rows go with the import job when the account is deleted.
CREATE TABLE statement_original_views (
    id               UUID        PRIMARY KEY,
    job_id           UUID        NOT NULL REFERENCES import_jobs(id) ON DELETE CASCADE,
    subject_user_id  UUID        NOT NULL,
    viewer_id        UUID        NOT NULL,
    queue            VARCHAR(16) NOT NULL,
    item_ref         VARCHAR(64) NOT NULL,
    format           VARCHAR(8)  NOT NULL,
    page_count       INT         NOT NULL,
    reason           VARCHAR(500) NOT NULL,
    opened_at        TIMESTAMPTZ NOT NULL,
    expires_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT statement_original_views_queue_valid CHECK (queue IN ('IMPORT', 'TRUST')),
    CONSTRAINT statement_original_views_format_valid CHECK (format IN ('PDF', 'CSV')),
    CONSTRAINT statement_original_views_pages_valid CHECK (page_count >= 0)
);

CREATE INDEX idx_statement_original_views_job ON statement_original_views (job_id, opened_at);
```

- [ ] **Step 2: Write the entity and repository**

```java
package com.finora.imports.original;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** One opening of a held statement's real pages. See V271 for what the row is for. */
@Entity
@Table(name = "statement_original_views")
public class StatementOriginalView {

    /** How long a view's page images can be fetched after it opens. */
    public static final Duration LIFETIME = Duration.ofMinutes(30);

    @Id private UUID id;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "subject_user_id", nullable = false) private UUID subjectUserId;
    @Column(name = "viewer_id", nullable = false) private UUID viewerId;
    @Column(nullable = false) private String queue;
    @Column(name = "item_ref", nullable = false) private String itemRef;
    @Column(nullable = false) private String format;
    @Column(name = "page_count", nullable = false) private int pageCount;
    @Column(nullable = false) private String reason;
    @Column(name = "opened_at", nullable = false) private Instant openedAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;

    protected StatementOriginalView() {}

    public static StatementOriginalView open(UUID jobId, UUID subjectUserId, UUID viewerId, String queue,
                                             String itemRef, String format, int pageCount, String reason,
                                             Instant now) {
        StatementOriginalView v = new StatementOriginalView();
        v.id = UUID.randomUUID();
        v.jobId = jobId;
        v.subjectUserId = subjectUserId;
        v.viewerId = viewerId;
        v.queue = queue;
        v.itemRef = itemRef;
        v.format = format;
        v.pageCount = pageCount;
        v.reason = reason;
        v.openedAt = now;
        v.expiresAt = now.plus(LIFETIME);
        return v;
    }

    /** Only the person who opened it, and only until it expires. */
    public boolean isLiveFor(UUID viewer, Instant now) {
        return viewerId.equals(viewer) && now.isBefore(expiresAt);
    }

    public UUID getId() { return id; }
    public UUID getJobId() { return jobId; }
    public UUID getSubjectUserId() { return subjectUserId; }
    public UUID getViewerId() { return viewerId; }
    public String getQueue() { return queue; }
    public String getItemRef() { return itemRef; }
    public String getFormat() { return format; }
    public int getPageCount() { return pageCount; }
    public String getReason() { return reason; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
```

```java
package com.finora.imports.original;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface StatementOriginalViewRepository extends JpaRepository<StatementOriginalView, UUID> {
    List<StatementOriginalView> findByJobIdOrderByOpenedAtAsc(UUID jobId);
}
```

- [ ] **Step 3: Write the failing IT**

Build the held job the way `AdminHeldImportDownloadIT.heldJob()` does: copy its `createUser` and
`heldJob` helpers and its `@TestPropertySource`, with a different filesystem root.

```java
@Test
void aSavedViewComesBackInOpeningOrderAndGoesWithItsJob() {
    ImportJob job = heldJob();
    Instant t0 = Instant.parse("2026-10-15T05:00:00Z");
    views.save(StatementOriginalView.open(job.getId(), job.getUserId(), UUID.randomUUID(), "IMPORT",
            job.getId().toString(), "PDF", 3, "Rows on page 2 are missing", t0.plusSeconds(60)));
    views.save(StatementOriginalView.open(job.getId(), job.getUserId(), UUID.randomUUID(), "IMPORT",
            job.getId().toString(), "PDF", 3, "Second look at page 2 rows", t0));

    assertThat(views.findByJobIdOrderByOpenedAtAsc(job.getId()))
            .extracting(StatementOriginalView::getOpenedAt)
            .containsExactly(t0, t0.plusSeconds(60));

    importJobRepository.delete(job);
    assertThat(views.findByJobIdOrderByOpenedAtAsc(job.getId())).isEmpty();
}

@Test
void aViewIsLiveOnlyForItsViewerAndOnlyForThirtyMinutes() {
    UUID viewer = UUID.randomUUID();
    Instant now = Instant.parse("2026-10-15T05:00:00Z");
    StatementOriginalView v = StatementOriginalView.open(UUID.randomUUID(), UUID.randomUUID(), viewer,
            "IMPORT", "x", "PDF", 1, "Rows on page 2 are missing", now);

    assertThat(v.isLiveFor(viewer, now.plus(Duration.ofMinutes(29)))).isTrue();
    assertThat(v.isLiveFor(viewer, now.plus(Duration.ofMinutes(30)))).isFalse();
    assertThat(v.isLiveFor(UUID.randomUUID(), now)).isFalse();
}
```

- [ ] **Step 4: Run it**

Run: `./mvnw verify -Dit.test=StatementOriginalViewRepositoryIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false`
(in `backend/`). Expected: PASS once the migration and entity exist. If the cascade delete fails
because other rows reference the job, delete the way the account-deletion path does, and note it.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V271__statement_original_views.sql backend/src/main/java/com/finora/imports/original backend/src/test/java/com/finora/imports/original/StatementOriginalViewRepositoryIT.java
git commit -m "feat(imports): a record of each time a held statement's real pages are opened"
```

---

### Task 2: Turning a statement into page images

**Files:**
- Create: `backend/src/main/java/com/finora/imports/original/OriginalStatementRenderer.java`
- Test: `backend/src/test/java/com/finora/imports/original/OriginalStatementRendererTest.java`

**Interfaces:**
- Produces (a Spring `@Component`):
  - `int pageCount(byte[] pdf)`
  - `byte[] renderPage(byte[] pdf, int pageNumber)` (1-based page number, returns PNG bytes)
  - `List<String> csvLines(byte[] csv)`
  - All of them throw `ApiException(HttpStatus.CONFLICT, ...)` when the file cannot be read,
    and `ApiException(HttpStatus.NOT_FOUND, ...)` for a page outside `1..pageCount`.

- [ ] **Step 1: Write the failing tests**

```java
class OriginalStatementRendererTest {

    private final OriginalStatementRenderer renderer = new OriginalStatementRenderer();

    private static byte[] pdfWithPages(int pages) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream s = new PDPageContentStream(doc, page)) {
                    s.beginText();
                    s.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    s.newLineAtOffset(72, 720);
                    s.showText("Synthetic page " + (i + 1));
                    s.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void countsPages() throws IOException {
        assertThat(renderer.pageCount(pdfWithPages(3))).isEqualTo(3);
    }

    @Test
    void rendersAPageAsAPng() throws IOException {
        byte[] png = renderer.renderPage(pdfWithPages(2), 2);
        assertThat(png).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isBetween(800, 1300);
    }

    @Test
    void aPageOutsideTheDocumentIsNotFound() throws IOException {
        byte[] pdf = pdfWithPages(2);
        assertThatThrownBy(() -> renderer.renderPage(pdf, 0))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> renderer.renderPage(pdf, 3))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void bytesThatAreNotAPdfAreAConflictNamingNoContent() {
        byte[] junk = "not a pdf".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> renderer.pageCount(junk))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).doesNotContain("not a pdf");
                });
    }

    @Test
    void aHugePageIsScaledDownToTheCap() throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage(new PDRectangle(5000, 5000)));
            doc.save(out);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(renderer.renderPage(out.toByteArray(), 1)));
            assertThat((long) image.getWidth() * image.getHeight())
                    .isLessThanOrEqualTo(OriginalStatementRenderer.MAX_PIXELS);
        }
    }

    @Test
    void csvLinesAreReturnedAsUtf8TextCappedAtTheLimit() {
        String many = IntStream.range(0, OriginalStatementRenderer.MAX_CSV_LINES + 5)
                .mapToObj(i -> "r" + i).collect(Collectors.joining("\n"));
        List<String> lines = renderer.csvLines(many.getBytes(StandardCharsets.UTF_8));
        assertThat(lines).hasSize(OriginalStatementRenderer.MAX_CSV_LINES);
        assertThat(lines.get(0)).isEqualTo("r0");
    }
}
```

`ApiException` must expose its status. If it has no `getStatus()`, use whatever accessor
`AdminHeldImportDownloadIT`'s neighbours use, and keep the assertions the same.

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw test -Dtest=OriginalStatementRendererTest` (in `backend/`). Expected: compilation
failure, because the class doesn't exist yet.

- [ ] **Step 3: Implement**

```java
package com.finora.imports.original;

import com.finora.exception.ApiException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Turns a held statement into what the admin portal shows: PNG page images for a PDF, text lines for
 * a CSV. The file itself is never sent. Messages name no content: the cause is logged by class name
 * only.
 */
@Component
public class OriginalStatementRenderer {

    /** 1.5 times a PDF point: an A4 page comes out about 893 pixels wide, readable on a laptop. */
    static final float SCALE = 1.5f;
    /** A page larger than this is scaled down, so one odd page cannot exhaust memory. */
    static final long MAX_PIXELS = 4_000_000L;
    static final int MAX_CSV_LINES = 5_000;

    public int pageCount(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            throw unreadable(e);
        }
    }

    public byte[] renderPage(byte[] pdf, int pageNumber) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            if (pageNumber < 1 || pageNumber > doc.getNumberOfPages()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "That page is not in this statement.");
            }
            PDRectangle box = doc.getPage(pageNumber - 1).getCropBox();
            float scale = SCALE;
            double pixels = (double) box.getWidth() * SCALE * box.getHeight() * SCALE;
            if (pixels > MAX_PIXELS) scale = (float) (SCALE * Math.sqrt(MAX_PIXELS / pixels));
            BufferedImage image = new PDFRenderer(doc).renderImage(pageNumber - 1, scale, ImageType.RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw unreadable(e);
        }
    }

    public List<String> csvLines(byte[] csv) {
        return new String(csv, StandardCharsets.UTF_8).lines().limit(MAX_CSV_LINES).toList();
    }

    private static ApiException unreadable(IOException e) {
        return new ApiException(HttpStatus.CONFLICT,
                "The statement could not be opened for viewing (" + e.getClass().getSimpleName() + ").");
    }
}
```

The `MAX_PIXELS` rounding can land a few pixels over. If `aHugePageIsScaledDownToTheCap` fails by
a handful of pixels, apply `Math.floor` to the scale with a 0.99 safety factor. Don't loosen the
test.

- [ ] **Step 4: Run them to see them pass**

Run: `./mvnw test -Dtest=OriginalStatementRendererTest`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/imports/original/OriginalStatementRenderer.java backend/src/test/java/com/finora/imports/original/OriginalStatementRendererTest.java
git commit -m "feat(imports): render a held statement's pages as images on the server"
```

---

### Task 3: The held queues hand over content for a view, audited first; downloads go

**Files:**
- Create: `backend/src/main/java/com/finora/imports/original/OriginalContent.java`
- Modify: `backend/src/main/java/com/finora/service/AdminHeldImportService.java`, which removes
  `download` (around line 154) and adds the two methods below
- Modify: `backend/src/main/java/com/finora/service/HeldStatementService.java`, which removes
  `download` (around line 1082) and adds the two methods below
- Modify: `backend/src/main/java/com/finora/controller/AdminHeldImportController.java` and
  `AdminHeldStatementController.java`, which remove `document(...)` and their now-unused imports
- Delete: `backend/src/test/java/com/finora/controller/AdminHeldImportDownloadIT.java`,
  `backend/src/test/java/com/finora/controller/AdminHeldStatementDownloadIT.java`. Their coverage
  moves to Task 6. Before deleting, list every test method in both and make sure each case
  (unauthenticated, plain user, support role, not held, locked, missing file) has a matching test
  in Task 3 or Task 6.
- Test: `backend/src/test/java/com/finora/service/OriginalContentAccessIT.java`

**Interfaces:**
- Produces:
  - `record OriginalContent(UUID jobId, UUID subjectUserId, String queue, String itemRef, String format, byte[] content)`.
    `toString()` must not print the bytes: override it to print the ids and `content.length`.
  - `AdminHeldImportService.contentForOriginalView(UUID viewerId, UUID jobId, String reason) : OriginalContent`
    (audited)
  - `AdminHeldImportService.contentForOpenView(UUID jobId) : OriginalContent` (not audited;
    requires the job still held)
  - `HeldStatementService.contentForOriginalView(UUID viewerId, String heldId, String reason) : OriginalContent`
    (audited)
  - `HeldStatementService.contentForOpenView(String heldId) : OriginalContent` (not audited;
    requires the hold still open)
- `queue` is `"IMPORT"` or `"TRUST"`. `itemRef` is the job id as text, or the `heldId`.

- [ ] **Step 1: Write the failing IT**

Use the helpers from the deleted download ITs: the filesystem storage `@TestPropertySource`,
`createUser`, `heldJob()`, and for the trust queue the setup `AdminHeldStatementDownloadIT` used
to make an open hold. Copy them before deleting those files.

```java
@Test
void anImportViewIsAuditedWithTheReasonBeforeTheRead() {
    ImportJob job = heldJob();
    User admin = createUser("ADMIN");

    OriginalContent content = heldImports.contentForOriginalView(admin.getId(), job.getId(),
            "Rows on page 2 are missing from the preview");

    assertThat(content.content()).isEqualTo(PDF_BYTES);
    assertThat(content.queue()).isEqualTo("IMPORT");
    assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId())).anySatisfy(e -> {
        assertThat(e.getAction()).isEqualTo("HELD_IMPORT_ORIGINAL_VIEWED");
        assertThat(e.getUserId()).isEqualTo(admin.getId());
        assertThat(e.getMetadata()).containsEntry("reason", "Rows on page 2 are missing from the preview");
    });
}

@Test
void aMissingFileIsAConflictAndTheAttemptIsStillAudited() {
    ImportJob job = heldJobWhoseStoredFileIsDeleted();   // store, then delete the stored object
    User admin = createUser("ADMIN");

    assertThatThrownBy(() -> heldImports.contentForOriginalView(admin.getId(), job.getId(),
            "Rows on page 2 are missing from the preview"))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId()))
            .extracting(AuditLog::getAction).contains("HELD_IMPORT_ORIGINAL_VIEWED");
}

@Test
void aJobThatIsNotHeldIsRefusedWithNoAudit() {
    ImportJob queued = queuedJob();   // as the deleted IT's not-held case built it
    User admin = createUser("ADMIN");

    assertThatThrownBy(() -> heldImports.contentForOriginalView(admin.getId(), queued.getId(),
            "Rows on page 2 are missing from the preview"))
            .isInstanceOf(ApiException.class);
    assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(queued.getId()))
            .extracting(AuditLog::getAction).doesNotContain("HELD_IMPORT_ORIGINAL_VIEWED");
}

@Test
void anOpenViewStopsOnceTheJobIsReleased() {
    ImportJob job = heldJob();
    heldImports.resolve(createUser("ADMIN").getId(), job.getId(), "We fixed the reader; please re-upload.");

    assertThatThrownBy(() -> heldImports.contentForOpenView(job.getId()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
}

@Test
void aLockedTrustHoldIsRefusedBeforeTheAudit() {
    HeldStatement held = lockedHoldWithoutSavedPassword();   // as the deleted IT built it
    User admin = createUser("ADMIN");

    assertThatThrownBy(() -> heldStatements.contentForOriginalView(admin.getId(), held.getHeldId(),
            "Rows on page 2 are missing from the preview"))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(held.getId()))
            .extracting(AuditLog::getAction).doesNotContain("TRUST_REVIEW_ORIGINAL_VIEWED");
}

@Test
void aFailedUnlockStillLeavesTheAuditButNoView() {
    ImportJob job = heldJobWithAWrongSavedPassword();   // a protected synthetic PDF, wrong password saved
    User admin = createUser("ADMIN");

    assertThatThrownBy(() -> heldImports.contentForOriginalView(admin.getId(), job.getId(),
            "Rows on page 2 are missing from the preview"))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId()))
            .extracting(AuditLog::getAction).contains("HELD_IMPORT_ORIGINAL_VIEWED");
}
```

Write the trust-queue equivalent of `anImportViewIsAuditedWithTheReasonBeforeTheRead` too,
asserting `TRUST_REVIEW_ORIGINAL_VIEWED` with `heldId` in the metadata.

For `heldJobWithAWrongSavedPassword`, look at how the blanked-copy ITs from #2168 built a
protected PDF with a saved password (search the test tree for `unlockedForBlankedCopy` or "saved
password did not open") and reuse it.

If `AuditLog`'s metadata accessor is named differently, use what `AuditService.record` writes to,
and keep the assertion.

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw verify -Dit.test=OriginalContentAccessIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false`.
Expected: compilation failure.

- [ ] **Step 3: Implement**

In `AdminHeldImportService`, replace `download` with:

```java
/**
 * The held job's real bytes for an Admin's in-browser view: the one path left that shows staff a
 * customer's statement. Audited with the typed reason in its own transaction before the bytes are
 * read, as the blanked copy is; a read that then fails leaves the record of the attempt. A saved
 * password that does not open the file is refused rather than handed over locked. The role pin
 * lives on the controller.
 */
@Transactional
public OriginalContent contentForOriginalView(UUID viewerId, UUID jobId, String reason) {
    ImportJob job = require(jobId);
    requireHeld(job, "opened");
    boolean savedPassword = "PDF".equalsIgnoreCase(job.getSourceFormat())
            && statementPasswordService.hasJobPassword(job.getId());
    auditService.recordEvenOnRollback(viewerId, "HELD_IMPORT_ORIGINAL_VIEWED", "ImportJob", jobId,
            Map.of("actorId", viewerId.toString(),
                    "subjectUserId", job.getUserId().toString(),
                    "reason", reason,
                    "savedPasswordOnFile", savedPassword));
    return new OriginalContent(job.getId(), job.getUserId(), "IMPORT", job.getId().toString(),
            job.getSourceFormat().toUpperCase(Locale.ROOT), readUnlocked(job, savedPassword));
}

/** The same bytes for a page of a view already opened (and audited). Not audited again; refuses a
 *  job no longer held, so a release ends every open view. */
@Transactional(readOnly = true)
public OriginalContent contentForOpenView(UUID jobId) {
    ImportJob job = require(jobId);
    requireHeld(job, "opened");
    boolean savedPassword = "PDF".equalsIgnoreCase(job.getSourceFormat())
            && statementPasswordService.hasJobPassword(job.getId());
    return new OriginalContent(job.getId(), job.getUserId(), "IMPORT", job.getId().toString(),
            job.getSourceFormat().toUpperCase(Locale.ROOT), readUnlocked(job, savedPassword));
}

private byte[] readUnlocked(ImportJob job, boolean savedPassword) {
    byte[] content;
    try {
        content = statementContentService.read(job);
    } catch (StatementStorageException e) {
        log.warn("Stored statement behind held import {} could not be read for a view", job.getId(), e);
        throw new ApiException(HttpStatus.CONFLICT,
                "The stored statement file behind held import " + job.getId() + " could not be read.");
    }
    return savedPassword
            ? statementPasswordService.unlockedForBlankedCopy(job, content, "held import " + job.getId())
            : content;
}
```

`requireHeld(job, "opened")`'s message comes out as "...cannot be opened". Check the existing
wording with `"downloaded"` and keep the sentence grammatical.

Make `contentForBlankedCopy` use `readUnlocked` too, so there is one read path. Its behaviour must
not change: re-run the blanked-copy ITs in Step 4.

`HeldStatementService` gets the same pair:
- `contentForOriginalView` calls `refuseIfLockedWithoutPassword(held, "opened")` before the audit
  (as `download` did).
- Audit action `TRUST_REVIEW_ORIGINAL_VIEWED`, entity `HeldStatement`, `held.getId()`, with
  `heldId` added to the details.
- `readStatement(held, job)` for the bytes, then `unlockedForBlankedCopy` when a password is saved.
- `contentForOpenView(heldId)` requires the hold still open, using the same check the hold's
  approve and reject paths use to refuse a decided hold.

Delete `HeldStatementService.DownloadedStatement` and the import queue's `DownloadedStatement` if
nothing else uses them (`grep -rn DownloadedStatement backend/src`). Delete both
`contentTypeFor` helpers if they become unused.

Remove `document(...)` from both controllers.

- [ ] **Step 4: Run it and the neighbouring suites**

Run: `./mvnw verify -Dit.test='OriginalContentAccessIT,AdminBlankedCopy*IT,AdminHeldImport*IT,AdminHeldStatement*IT' -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false`.
Expected: PASS, with the blanked-copy ITs unchanged.

- [ ] **Step 5: Commit**

```bash
git add -A backend/src
git commit -m "feat(imports): held queues hand an Admin a view's content, audited with a reason; downloads removed"
```

---

### Task 4: The view service

**Files:**
- Create: `backend/src/main/java/com/finora/imports/original/OriginalStatementViewService.java`
- Create: `backend/src/main/java/com/finora/imports/original/OriginalView.java`
- Test: `backend/src/test/java/com/finora/imports/original/OriginalStatementViewServiceTest.java`
  (unit, Mockito)

**Interfaces:**
- Consumes:
  - from Task 1: `StatementOriginalView`, `StatementOriginalViewRepository`;
  - from Task 2: `OriginalStatementRenderer`;
  - from Task 3: `OriginalContent` and the four `content...` methods.
- Produces:
  - `record OriginalView(UUID viewId, String format, int pageCount, Instant expiresAt)`
  - `OriginalView openImport(UUID viewerId, UUID jobId, String reason)`
  - `OriginalView openTrust(UUID viewerId, String heldId, String reason)`
  - `byte[] page(UUID viewerId, UUID viewId, int pageNumber)` (PDF only, else 409)
  - `List<String> csv(UUID viewerId, UUID viewId)` (CSV only, else 409)
  - `static String validReason(String raw)`: trims; throws `ApiException(BAD_REQUEST, "Type a reason of 15 to 500 characters.")`
  - Fires `OriginalViewOpened(StatementOriginalView view)`, a Spring application event published
    after the view row is saved. Task 5 listens with
    `@TransactionalEventListener(phase = AFTER_COMMIT)`.

- [ ] **Step 1: Write the failing tests**

```java
@ExtendWith(MockitoExtension.class)
class OriginalStatementViewServiceTest {

    @Mock AdminHeldImportService heldImports;
    @Mock HeldStatementService heldStatements;
    @Mock StatementOriginalViewRepository views;
    @Mock OriginalStatementRenderer renderer;
    @Mock ApplicationEventPublisher events;
    Clock clock = Clock.fixed(Instant.parse("2026-10-15T05:00:00Z"), ZoneOffset.UTC);
    OriginalStatementViewService service;

    UUID viewer = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    byte[] pdf = {1, 2, 3};
    String reason = "Rows on page 2 are missing from the preview";

    @BeforeEach
    void setUp() {
        service = new OriginalStatementViewService(heldImports, heldStatements, views, renderer, events, clock);
        lenient().when(views.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void openingAPdfSavesAViewAndPublishesTheEvent() {
        when(heldImports.contentForOriginalView(viewer, jobId, reason))
                .thenReturn(new OriginalContent(jobId, UUID.randomUUID(), "IMPORT", jobId.toString(), "PDF", pdf));
        when(renderer.pageCount(pdf)).thenReturn(4);

        OriginalView view = service.openImport(viewer, jobId, "  " + reason + "  ");

        assertThat(view.pageCount()).isEqualTo(4);
        assertThat(view.expiresAt()).isEqualTo(Instant.parse("2026-10-15T05:30:00Z"));
        verify(views).save(argThat(v -> v.getReason().equals(reason) && v.getViewerId().equals(viewer)));
        verify(events).publishEvent(any(OriginalViewOpened.class));
    }

    @Test
    void aShortReasonIsRefusedBeforeTheQueueIsAsked() {
        assertThatThrownBy(() -> service.openImport(viewer, jobId, "   too short   "))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(heldImports, views, events);
    }

    @Test
    void noNoticeWhenTheReadFails() {
        when(heldImports.contentForOriginalView(viewer, jobId, reason))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "could not be read"));
        assertThatThrownBy(() -> service.openImport(viewer, jobId, reason)).isInstanceOf(ApiException.class);
        verifyNoInteractions(views, events);
    }

    @Test
    void noNoticeWhenThePdfCannotBeRendered() {
        when(heldImports.contentForOriginalView(viewer, jobId, reason))
                .thenReturn(new OriginalContent(jobId, UUID.randomUUID(), "IMPORT", jobId.toString(), "PDF", pdf));
        when(renderer.pageCount(pdf)).thenThrow(new ApiException(HttpStatus.CONFLICT, "could not be opened"));
        assertThatThrownBy(() -> service.openImport(viewer, jobId, reason)).isInstanceOf(ApiException.class);
        verifyNoInteractions(views, events);
    }

    @Test
    void anotherViewersIdIsNotFound() {
        StatementOriginalView v = StatementOriginalView.open(jobId, UUID.randomUUID(), viewer, "IMPORT",
                jobId.toString(), "PDF", 2, reason, clock.instant());
        when(views.findById(v.getId())).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> service.page(UUID.randomUUID(), v.getId(), 1))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(heldImports, renderer);
    }

    @Test
    void anExpiredViewIsNotFound() {
        StatementOriginalView v = StatementOriginalView.open(jobId, UUID.randomUUID(), viewer, "IMPORT",
                jobId.toString(), "PDF", 2, reason, clock.instant().minus(Duration.ofMinutes(31)));
        when(views.findById(v.getId())).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> service.page(viewer, v.getId(), 1))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void aPageIsRenderedFromFreshContentForItsOwnQueue() {
        StatementOriginalView v = StatementOriginalView.open(jobId, UUID.randomUUID(), viewer, "IMPORT",
                jobId.toString(), "PDF", 2, reason, clock.instant());
        when(views.findById(v.getId())).thenReturn(Optional.of(v));
        when(heldImports.contentForOpenView(jobId))
                .thenReturn(new OriginalContent(jobId, v.getSubjectUserId(), "IMPORT", jobId.toString(), "PDF", pdf));
        when(renderer.renderPage(pdf, 2)).thenReturn(new byte[] {9});

        assertThat(service.page(viewer, v.getId(), 2)).containsExactly(9);
    }

    @Test
    void askingForCsvLinesOfAPdfViewIsAConflict() {
        StatementOriginalView v = StatementOriginalView.open(jobId, UUID.randomUUID(), viewer, "IMPORT",
                jobId.toString(), "PDF", 2, reason, clock.instant());
        when(views.findById(v.getId())).thenReturn(Optional.of(v));

        assertThatThrownBy(() -> service.csv(viewer, v.getId()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }
}
```

Add `openingATrustHoldUsesTheTrustQueue`. It mirrors the first test through
`heldStatements.contentForOriginalView`, and a page fetch on that view calls
`heldStatements.contentForOpenView(heldId)`.

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw test -Dtest=OriginalStatementViewServiceTest`. Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package com.finora.imports.original;

/** Published once a view row is saved; Task 5's listener sends the emails after commit. */
public record OriginalViewOpened(StatementOriginalView view) {}
```

```java
@Service
public class OriginalStatementViewService {

    private final AdminHeldImportService heldImports;
    private final HeldStatementService heldStatements;
    private final StatementOriginalViewRepository views;
    private final OriginalStatementRenderer renderer;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    // constructor assigning all six; a second @Autowired constructor without Clock passes Clock.systemUTC()

    public static String validReason(String raw) {
        String reason = raw == null ? "" : raw.strip();
        if (reason.length() < 15 || reason.length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Type a reason of 15 to 500 characters.");
        }
        return reason;
    }

    @Transactional
    public OriginalView openImport(UUID viewerId, UUID jobId, String rawReason) {
        String reason = validReason(rawReason);
        return open(viewerId, heldImports.contentForOriginalView(viewerId, jobId, reason), reason);
    }

    @Transactional
    public OriginalView openTrust(UUID viewerId, String heldId, String rawReason) {
        String reason = validReason(rawReason);
        return open(viewerId, heldStatements.contentForOriginalView(viewerId, heldId, reason), reason);
    }

    private OriginalView open(UUID viewerId, OriginalContent content, String reason) {
        int pages = "PDF".equals(content.format()) ? renderer.pageCount(content.content()) : 0;
        StatementOriginalView view = views.save(StatementOriginalView.open(content.jobId(),
                content.subjectUserId(), viewerId, content.queue(), content.itemRef(), content.format(),
                pages, reason, clock.instant()));
        events.publishEvent(new OriginalViewOpened(view));
        return new OriginalView(view.getId(), view.getFormat(), view.getPageCount(), view.getExpiresAt());
    }

    @Transactional(readOnly = true)
    public byte[] page(UUID viewerId, UUID viewId, int pageNumber) {
        StatementOriginalView view = live(viewerId, viewId);
        if (!"PDF".equals(view.getFormat())) throw new ApiException(HttpStatus.CONFLICT, "This statement is a CSV file.");
        return renderer.renderPage(fresh(view).content(), pageNumber);
    }

    @Transactional(readOnly = true)
    public List<String> csv(UUID viewerId, UUID viewId) {
        StatementOriginalView view = live(viewerId, viewId);
        if (!"CSV".equals(view.getFormat())) throw new ApiException(HttpStatus.CONFLICT, "This statement is a PDF file.");
        return renderer.csvLines(fresh(view).content());
    }

    private StatementOriginalView live(UUID viewerId, UUID viewId) {
        return views.findById(viewId)
                .filter(v -> v.isLiveFor(viewerId, clock.instant()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "This view has ended. Open the statement again."));
    }

    private OriginalContent fresh(StatementOriginalView view) {
        return "IMPORT".equals(view.getQueue())
                ? heldImports.contentForOpenView(view.getJobId())
                : heldStatements.contentForOpenView(view.getItemRef());
    }
}
```

Note on transactions: `contentForOriginalView`'s audit uses `recordEvenOnRollback`, so a later
failure in `open` (rendering) rolls back the view row but keeps the audit entry. That is intended.

- [ ] **Step 4: Run them to see them pass**

Run: `./mvnw test -Dtest=OriginalStatementViewServiceTest`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/imports/original backend/src/test/java/com/finora/imports/original/OriginalStatementViewServiceTest.java
git commit -m "feat(imports): open a short view of a held statement for one Admin, with a reason"
```

---

### Task 5: Telling the Super Admins and the customer

**Files:**
- Create: `backend/src/main/resources/db/migration/V272__import_statement_opened_for_fix_notification.sql`
- Modify: `backend/src/main/java/com/finora/notification/domain/NotificationType.java` (add
  `IMPORT_STATEMENT_OPENED_FOR_FIX` with a doc comment in the style of its neighbours)
- Modify: `backend/src/main/java/com/finora/notification/provider/EmailNotificationProvider.java`
  (add the type to `SUPPORT_SENDER_TYPES`)
- Modify: `backend/src/main/java/com/finora/service/StatementStatusNotifier.java` (add
  `notifyOpenedForFix`)
- Modify: `backend/src/main/java/com/finora/repository/UserRepository.java` (add
  `findActiveUsersWithRole`)
- Modify: `backend/src/main/java/com/finora/service/HeldItemAdminAlertService.java` (add
  `alertOriginalOpened`)
- Create: `backend/src/main/java/com/finora/imports/original/OriginalViewNotices.java` (the
  after-commit listener)
- Test: `backend/src/test/java/com/finora/imports/original/OriginalViewNoticesIT.java`

**Interfaces:**
- Consumes from Task 4: `OriginalViewOpened`, `StatementOriginalView`.
- Produces:
  - `StatementStatusNotifier.notifyOpenedForFix(UUID userId, UUID jobId, LocalDate day)`
  - `UserRepository.findActiveUsersWithRole(String roleName, String activeStatus) : List<User>`
  - `HeldItemAdminAlertService.alertOriginalOpened(UUID viewId)`

- [ ] **Step 1: Write the migration**

```sql
-- Seeds copy for NotificationType.IMPORT_STATEMENT_OPENED_FOR_FIX: the customer is told, after the
-- fact, that an administrator opened the statement they uploaded to fix a reading problem
-- (project 2 of docs/superpowers/specs/2026-10-14-fixing-statements-without-the-real-statement-design.md).
-- It names the date and the purpose, never who. Consent before opening is parked by the owner
-- until the user base grows; this notice is the interim. {{date}} is "15 Oct 2026", India time.
INSERT INTO notification_templates (id, type, channel, title_template, body_template) VALUES
    (gen_random_uuid(), 'IMPORT_STATEMENT_OPENED_FOR_FIX', 'EMAIL',
     'We opened your statement to fix a reading problem',
     'On {{date}}, a member of our team opened the statement you uploaded so we could fix a problem '
     'reading it. Only administrators can do this, and every opening is recorded. Nothing in your '
     'accounts was changed by it.'),
    (gen_random_uuid(), 'IMPORT_STATEMENT_OPENED_FOR_FIX', 'PUSH',
     'Your statement was opened',
     'On {{date}} our team opened it to fix a reading problem.');
```

Check V155 and V263 for any other columns `notification_templates` needs (for example `active`
or a category), and include them the same way.

- [ ] **Step 2: Write the failing IT**

Use the IT harness other notification ITs use to capture sent emails and queued notifications.
Search the test tree for a test of `StatementStatusNotifier.notifyHeld` or
`HeldItemAdminAlertService` and reuse its capture of `EmailProvider` (a `@MockitoBean` or a
recording fake).

```java
@Test
void anOpeningEmailsEverySuperAdminAndNoOneElse() {
    User superAdmin = createUser("SUPER_ADMIN");
    User otherAdmin = createUser("ADMIN");
    StatementOriginalView view = savedView(otherAdmin, "Rows on page 2 are missing from the preview");

    alerts.alertOriginalOpened(view.getId());

    assertThat(sentTo()).contains(superAdmin.getEmail()).doesNotContain(otherAdmin.getEmail());
    String html = lastHtmlTo(superAdmin.getEmail());
    assertThat(html).contains(otherAdmin.getEmail()).contains("Rows on page 2 are missing from the preview");
    assertThat(html).doesNotContain(ownerOf(view).getEmail()).doesNotContain(fileNameOf(view));
}

@Test
void theReasonIsEscapedInTheEmail() {
    User superAdmin = createUser("SUPER_ADMIN");
    StatementOriginalView view = savedView(createUser("ADMIN"), "<script>x</script> rows missing on page 2");

    alerts.alertOriginalOpened(view.getId());

    assertThat(lastHtmlTo(superAdmin.getEmail()))
            .doesNotContain("<script>").contains("&lt;script&gt;");
}

@Test
void twoOpeningsOnOneDaySendOneCustomerNotice() {
    StatementOriginalView first = savedView(createUser("ADMIN"), "Rows on page 2 are missing from the preview");
    StatementOriginalView second = savedViewForSameJob(first, "Second look at the rows on page 2");

    notices.onOpened(new OriginalViewOpened(first));
    notices.onOpened(new OriginalViewOpened(second));

    assertThat(notificationsFor(first.getSubjectUserId(), NotificationType.IMPORT_STATEMENT_OPENED_FOR_FIX))
            .hasSize(1);   // one logical notification; each channel row counts once under the dedupe key
}

@Test
void theCustomerNoticeNamesTheDateInIndiaTimeAndNoOne() {
    // A view opened at 2026-10-14T20:00:00Z is 15 Oct 2026 in India.
    StatementOriginalView view = savedViewAt(createUser("ADMIN"), Instant.parse("2026-10-14T20:00:00Z"));

    notices.onOpened(new OriginalViewOpened(view));

    Notification pushed = onlyNotificationFor(view.getSubjectUserId(), NotificationChannel.PUSH);
    assertThat(pushed.getMessage()).isEqualTo("On 15 Oct 2026 our team opened it to fix a reading problem.");
}
```

`notificationsFor`, `onlyNotificationFor`, `sentTo` and `lastHtmlTo` are small private helpers over
the repositories and captures the harness gives you. Write them in this IT.

- [ ] **Step 3: Implement**

`UserRepository`:

```java
/** Active accounts holding {@code roleName} by either grant mechanism -- the same rule as
 *  {@link #countActiveUsersWithRole}, listed rather than counted. */
@Query("""
       SELECT DISTINCT u FROM User u LEFT JOIN u.roles r
       WHERE (u.role = :roleName OR r.name = :roleName) AND u.status = :activeStatus
       """)
List<User> findActiveUsersWithRole(@Param("roleName") String roleName,
                                   @Param("activeStatus") String activeStatus);
```

Pass the same active-status constant `RoleService` passes to `countActiveUsersWithRole`.

`StatementStatusNotifier`:

```java
private static final DateTimeFormatter NOTICE_DATE =
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

/** The customer is told, after the fact, that an administrator opened their statement to fix a
 *  reading problem. One per job per India day: a second opening that day says nothing new. */
public void notifyOpenedForFix(UUID userId, UUID jobId, LocalDate day) {
    notificationService.request(NotificationRequest.of(
            userId,
            NotificationType.IMPORT_STATEMENT_OPENED_FOR_FIX,
            NotificationCategory.FINANCIAL,
            NotificationPriority.NORMAL,
            "IMPORT_OPENED_FOR_FIX_" + jobId + "_" + day,
            Set.of(NotificationChannel.PUSH, NotificationChannel.EMAIL),
            Map.of("date", NOTICE_DATE.format(day))));
}
```

`HeldItemAdminAlertService.alertOriginalOpened(UUID viewId)`:
- Load the view; log and return if it's gone.
- Load the viewer's email.
- Subject: `"A held statement was opened — " + itemRef`, with a real em dash as the neighbours use.
- HTML: a list with Opened by, When (`formatTimestamp(openedAt)`), Item (a link built like the
  other alerts' links, to `/held-imports/{jobId}` or `/held-statements/{heldId}`), Pages and
  Reason. Every value goes through `escape(...)`.
- Send to `userRepository.findActiveUsersWithRole(User.SUPER_ADMIN_ROLE, <active status>)`, one
  email each, with the same per-recipient try/catch and masking as `sendToRecipients`. Extract a
  shared private `send(List<User>, subject, html)` from `sendToRecipients` rather than copying it.

`OriginalViewNotices`:

```java
@Component
public class OriginalViewNotices {
    private final HeldItemAdminAlertService alerts;
    private final StatementStatusNotifier notifier;

    // constructor

    /** After the opening commits: an opening that rolled back never tells anyone it happened. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOpened(OriginalViewOpened event) {
        StatementOriginalView view = event.view();
        try {
            alerts.alertOriginalOpened(view.getId());
        } catch (RuntimeException e) {
            log.warn("Super Admin alert for original view {} failed", view.getId(), e);
        }
        try {
            notifier.notifyOpenedForFix(view.getSubjectUserId(), view.getJobId(),
                    view.getOpenedAt().atZone(UserZone.DEFAULT).toLocalDate());
        } catch (RuntimeException e) {
            log.warn("Customer notice for original view {} failed", view.getId(), e);
        }
    }
}
```

If the IT calls `onOpened` directly outside a transaction, it runs straight away. That's fine for
the IT. Task 6's IT proves the after-commit path end to end.

- [ ] **Step 4: Run it**

Run: `./mvnw verify -Dit.test=OriginalViewNoticesIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false`.
Expected: PASS. Then run `./mvnw test`: any test that lists every `NotificationType` (a template
coverage or channel test) must now pass with the new type, not be skipped.

- [ ] **Step 5: Commit**

```bash
git add -A backend/src
git commit -m "feat(imports): opening a held statement emails the Super Admins and tells the customer"
```

---

### Task 6: The endpoints

**Files:**
- Create: `backend/src/main/java/com/finora/controller/AdminOriginalViewController.java`
- Create: `backend/src/main/java/com/finora/dto/OriginalViewDto.java`
- Test: `backend/src/test/java/com/finora/controller/AdminOriginalViewIT.java`

**Interfaces:**
- Consumes from Task 4: `OriginalStatementViewService`, `OriginalView`.
- Produces (HTTP):
  - `POST /api/v1/admin/held-imports/{jobId}/original-view` with body `{ "reason": string }`.
    Returns `ApiResponse<OriginalViewDto.Opened>` = `{ viewId, format, pageCount, expiresAt }`.
  - `POST /api/v1/admin/held-statements/{heldId}/original-view`: same shape.
  - `GET /api/v1/admin/original-views/{viewId}/pages/{pageNumber}`: `image/png`.
  - `GET /api/v1/admin/original-views/{viewId}/csv`: `ApiResponse<OriginalViewDto.CsvLines>` =
    `{ lines: string[] }`.

- [ ] **Step 1: Write the failing IT**

Same harness as Task 3's IT: filesystem storage, `createUser`, held job and open hold. The stored
bytes must be a real 2-page PDF built with PDFBox (as in Task 2's `pdfWithPages`), not the
`"%PDF-1.4 ..."` stub.

```java
@Test void openingRefusesAnUnauthenticatedCaller()                 // 401
@Test void openingRefusesAPlainUser()                              // 403
@Test void openingRefusesASupportRoleHoldingOnlyTheQueuePermission() // 403: build a role with IMPORT_TRIAGE_MANAGE only, the way the deleted IT did
@Test void aBlankReasonIsRefusedBeforeAnything()                   // reason "     " -> 400, no HELD_IMPORT_ORIGINAL_VIEWED audit row, no view row
@Test void anAdminOpensAPdfAndGetsItsPagesAsImages()               // 200, pageCount 2; GET page 1 -> image/png, Cache-Control no-store, no attachment disposition
@Test void aPageOutOfRangeIsNotFound()                             // page 3 -> 404
@Test void anotherAdminCannotUseTheView()                          // second ADMIN, same viewId -> 404
@Test void aPageOfAReleasedItemIsRefused()                         // open, resolve the job, GET page 1 -> 409
@Test void aCsvHoldIsShownAsLines()                                // a held CSV job -> format CSV, pageCount 0; GET csv -> its lines
@Test void theTrustQueueOpensToo()                                 // POST held-statements/{heldId}/original-view -> 200, audit TRUST_REVIEW_ORIGINAL_VIEWED
@Test void theOldDownloadEndpointsAreGone()                        // GET .../document on both queues -> 404 (or 405), never 200
@Test void anOpeningSendsTheSuperAdminEmailAndTheCustomerNoticeAfterCommit() // through HTTP: one email to a SUPER_ADMIN, one IMPORT_STATEMENT_OPENED_FOR_FIX for the owner
```

Write each test body fully. The method name is the assertion. Example of one written out:

```java
@Test
void anAdminOpensAPdfAndGetsItsPagesAsImages() {
    ImportJob job = heldJobWithPdf(pdfWithPages(2));
    User admin = createUser("ADMIN");

    ResponseEntity<Map> opened = restTemplate.exchange(
            "/api/v1/admin/held-imports/" + job.getId() + "/original-view", HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "Rows on page 2 are missing from the preview"), bearerFor(admin)),
            Map.class);
    assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> data = (Map<?, ?>) opened.getBody().get("data");
    assertThat(data.get("pageCount")).isEqualTo(2);

    ResponseEntity<byte[]> page = restTemplate.exchange(
            "/api/v1/admin/original-views/" + data.get("viewId") + "/pages/1", HttpMethod.GET,
            new HttpEntity<>(bearerFor(admin)), byte[].class);
    assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(page.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    assertThat(page.getHeaders().getCacheControl()).contains("no-store");
    assertThat(page.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).isNull();
}
```

If `ApiResponse` wraps differently from `data`, follow what other admin ITs read.

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw verify -Dit.test=AdminOriginalViewIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false`.
Expected: 404s, because the controller doesn't exist yet.

- [ ] **Step 3: Implement**

```java
/**
 * The one way staff see a customer's real statement: page images in the browser, opened by an
 * Admin or Super Admin with a typed reason, for 30 minutes, for that person only. Each queue's
 * opening restates its permission and the role pin in one expression -- a method-level rule
 * replaces the class rule, it does not add to it. Page fetches need only the role: the view row
 * they name was opened under the queue's rule, and belongs to the caller or is a 404.
 * Rendering is CPU work on the request thread, so it takes an import slot like the blanked copy.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminOriginalViewController {

    @PreAuthorize("hasAuthority('IMPORT_TRIAGE_MANAGE') and hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/held-imports/{jobId}/original-view")
    public ApiResponse<OriginalViewDto.Opened> openImport(@PathVariable UUID jobId,
                                                          @RequestBody OriginalViewDto.OpenRequest body) throws Exception {
        UUID viewer = currentUser.id();
        return ApiResponse.ok(OriginalViewDto.Opened.of(gated(() -> views.openImport(viewer, jobId, body.reason()))));
    }

    @PreAuthorize("hasAuthority('TRUST_REVIEW_MANAGE') and hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/held-statements/{heldId}/original-view")
    public ApiResponse<OriginalViewDto.Opened> openTrust(@PathVariable String heldId,
                                                         @RequestBody OriginalViewDto.OpenRequest body) throws Exception {
        UUID viewer = currentUser.id();
        return ApiResponse.ok(OriginalViewDto.Opened.of(gated(() -> views.openTrust(viewer, heldId, body.reason()))));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping(value = "/original-views/{viewId}/pages/{pageNumber}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> page(@PathVariable UUID viewId, @PathVariable int pageNumber) throws Exception {
        UUID viewer = currentUser.id();
        byte[] png = gated(() -> views.page(viewer, viewId, pageNumber));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(png);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/original-views/{viewId}/csv")
    public ResponseEntity<ApiResponse<OriginalViewDto.CsvLines>> csv(@PathVariable UUID viewId) {
        UUID viewer = currentUser.id();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(new OriginalViewDto.CsvLines(views.csv(viewer, viewId))));
    }

    // gated(...) exactly as AdminBlankedCopyController.gated, with BUSY_MESSAGE
    // "The server is busy reading statements. Try again in a minute."
}
```

Set `Cache-Control: no-store` on both POST responses too. Use the same `ResponseEntity` wrapping,
or an existing no-store response advice if the codebase has one (search `noStore`).

`OriginalViewDto`:
- `record OpenRequest(String reason)`;
- `record Opened(UUID viewId, String format, int pageCount, Instant expiresAt)` with
  `static Opened of(OriginalView v)`;
- `record CsvLines(List<String> lines)`.

Add the controller to any security or route allowlist tests that enumerate admin endpoints (search
the test tree for `held-imports/{jobId}/document`; those references must now point at the new
endpoints or be removed).

- [ ] **Step 4: Run it and the full backend suite**

Run: `./mvnw verify` (in `backend/`). Expected: unit and integration tests run with 0 failures.
Note the counts for the PR.

- [ ] **Step 5: Commit**

```bash
git add -A backend/src
git commit -m "feat(imports): Admins view a held statement's pages in the browser, never download them"
```

---

### Task 7: The customer sees when their statement was opened (web)

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/jobs/ImportJobDto.java`: append
  `List<LocalDate> openedForFixOn` to `Timeline` and pass it through `Timeline.of`
- Modify: `backend/src/main/java/com/finora/imports/jobs/ImportJobService.java`, `timeline(...)`:
  load the dates
- Modify: `frontend/src/components/ImportTimeline.tsx`: show one line per date
- Test: extend the existing timeline IT (search `ImportJobService.timeline` or
  `/timeline` in `backend/src/test`), and `frontend/src/components/ImportTimeline.test.tsx`
  (create it if it doesn't exist)

**Interfaces:**
- Consumes from Task 1: `StatementOriginalViewRepository.findByJobIdOrderByOpenedAtAsc`.
- Produces: `Timeline.openedForFixOn`, distinct India dates in ascending order, empty when none.

- [ ] **Step 1: Write the failing tests**

Backend, in the timeline IT:

```java
@Test
void theTimelineListsEachDayTheStatementWasOpenedOnce() {
    ImportJob job = heldJobOwnedBy(owner);
    views.save(StatementOriginalView.open(job.getId(), owner.getId(), UUID.randomUUID(), "IMPORT",
            job.getId().toString(), "PDF", 1, "Rows on page 2 are missing", Instant.parse("2026-10-14T20:00:00Z")));
    views.save(StatementOriginalView.open(job.getId(), owner.getId(), UUID.randomUUID(), "IMPORT",
            job.getId().toString(), "PDF", 1, "Second look at page 2 rows", Instant.parse("2026-10-15T09:00:00Z")));

    assertThat(importJobService.timeline(owner.getId(), job.getId()).openedForFixOn())
            .containsExactly(LocalDate.parse("2026-10-15"));
}

@Test
void anotherUsersTimelineIsStillNotFound() { /* the existing ownership rule is unchanged */ }
```

Frontend, in `ImportTimeline.test.tsx`: mock `importJobsApi.timeline` to resolve with one stage and
`openedForFixOn: ['2026-10-15']`, render, and expect
`screen.getByText('Opened by our team on 15 Oct 2026 to fix a reading problem.')`. With
`openedForFixOn: []`, expect `screen.queryByText(/Opened by our team/)` to be null.

- [ ] **Step 2: Run them to see them fail**

Backend: `./mvnw verify -Dit.test=<the timeline IT> -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false`.
Frontend (Node 22, in `frontend/`): `npx vitest run src/components/ImportTimeline.test.tsx`.

- [ ] **Step 3: Implement**

In `ImportJobService.timeline`, after the ownership check:

```java
List<LocalDate> openedOn = originalViews.findByJobIdOrderByOpenedAtAsc(jobId).stream()
        .map(v -> v.getOpenedAt().atZone(UserZone.DEFAULT).toLocalDate())
        .distinct()
        .toList();
return ImportJobDto.Timeline.of(job, stageRepository.findByJobIdOrderByRecordedAtAsc(jobId), openedOn);
```

Keep the old two-argument `Timeline.of` only if other callers need it. If they do, pass
`List.of()` explicitly from them rather than hiding a default.

In `ImportTimeline.tsx`, under the stages list, render for each date:
`Opened by our team on {formatted} to fix a reading problem.`
- Format with `new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })`
  on `new Date(date + 'T00:00:00Z')`, which gives `15 Oct 2026`. The date is already the India
  day, so format it in UTC and don't shift it.
- Use the component's existing muted-text style. No icon that suggests danger.
- Render the line even when `stages` is empty. Check the early `return null` at line ~143 and add
  `&& timeline.openedForFixOn.length === 0` to it.
- Type the field on the frontend's timeline type (`ImportJobTimeline`). Task 8 regenerates
  `generated-types.ts`. Until then, add the field to whichever hand-written type the component
  reads.

- [ ] **Step 4: Run them to see them pass**

Run the same two commands. Expected: PASS. Then run in `frontend/` (Node 22): `npm run lint`,
`npm run build`, `npx vitest run`.

- [ ] **Step 5: Commit**

```bash
git add -A backend/src frontend/src
git commit -m "feat(imports): a customer's import page says when their statement was opened to fix a reading problem"
```

---

### Task 8: Contract and admin portal viewer

**Files:**
- Modify (regenerated): `backend/openapi/openapi.json`,
  `admin-portal/src/api/generated-types.ts`, `frontend/src/api/generated-types.ts`,
  `mobile/src/api/generated-types.ts`
- Modify: `admin-portal/src/api/endpoints.ts`: remove both `download` functions and add
  `adminOriginalViewApi`
- Create: `admin-portal/src/components/OriginalStatementViewer.tsx`
- Create: `admin-portal/src/components/OriginalStatementViewer.test.tsx`
- Modify: `admin-portal/src/pages/HeldImports.tsx` and `admin-portal/src/pages/HeldStatementDetail.tsx`:
  replace the download button with "View original"
- Modify: their tests (`HeldImports.test.tsx`, `HeldStatementDetail.test.tsx`), so that every
  assertion about downloading now asserts the viewer

**Interfaces:**
- Consumes the Task 6 endpoints.
- Produces:
  - `adminOriginalViewApi.openImport(jobId, reason)` and `adminOriginalViewApi.openTrust(heldId, reason)`,
    both returning `Promise<{ viewId; format; pageCount; expiresAt }>`
  - `adminOriginalViewApi.pageBlob(viewId, n) : Promise<Blob>`
  - `adminOriginalViewApi.csv(viewId) : Promise<string[]>`
  - `<OriginalStatementViewer queue="IMPORT"|"TRUST" itemId={string} onClose={() => void} />`

- [ ] **Step 1: Regenerate the contract**

Follow `docs/engineering/openapi-contracts.md` "Developer workflow":
1. Start local Postgres.
2. In `backend/`, run `./mvnw -DskipTests package` and then `./scripts/generate-openapi-spec.sh`.
3. Under Node 22, run `npm run generate:types` in `admin-portal/`, `frontend/` and `mobile/`.

Read the diff. It must add the three new paths and `openedForFixOn`, remove the two `/document`
paths, and change nothing else. If the mobile generated file changes only by those additions, keep
it. Mobile code reads none of them, so no app release is needed. Say so in the PR.

- [ ] **Step 2: Write the failing viewer tests**

```tsx
describe('OriginalStatementViewer', () => {
  it('asks for a reason first and says who will be told', () => {
    render(<OriginalStatementViewer queue="IMPORT" itemId="job-1" onClose={vi.fn()} />);
    expect(screen.getByText(/Super Admins will be emailed/)).toBeInTheDocument();
    expect(screen.getByText(/The customer will be told the date/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Open statement' })).toBeDisabled();
  });

  it('keeps the button off until the reason is 15 characters', async () => {
    render(<OriginalStatementViewer queue="IMPORT" itemId="job-1" onClose={vi.fn()} />);
    await userEvent.type(screen.getByLabelText('Reason'), 'too short');
    expect(screen.getByRole('button', { name: 'Open statement' })).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Reason'), ' rows on page 2');
    expect(screen.getByRole('button', { name: 'Open statement' })).toBeEnabled();
  });

  it('shows each page as an image and offers no download', async () => {
    vi.spyOn(adminOriginalViewApi, 'openImport').mockResolvedValue(
      { viewId: 'v1', format: 'PDF', pageCount: 2, expiresAt: '2026-10-15T05:30:00Z' });
    vi.spyOn(adminOriginalViewApi, 'pageBlob').mockResolvedValue(new Blob(['x'], { type: 'image/png' }));
    render(<OriginalStatementViewer queue="IMPORT" itemId="job-1" onClose={vi.fn()} />);
    await userEvent.type(screen.getByLabelText('Reason'), 'Rows on page 2 are missing');
    await userEvent.click(screen.getByRole('button', { name: 'Open statement' }));
    expect(await screen.findAllByRole('img', { name: /Page \d of 2/ })).toHaveLength(2);
    expect(screen.queryByRole('link', { name: /download/i })).toBeNull();
    expect(screen.queryByRole('button', { name: /download/i })).toBeNull();
  });

  it('shows a CSV as lines', async () => { /* openImport -> format CSV; csv -> ['a,b', 'c,d']; both lines visible in a <pre> */ });

  it('says the view has ended on a 404 and frees the image URLs on close', async () => {
    /* pageBlob rejects with a 404 -> "This view has ended. Open the statement again." visible;
       spy URL.revokeObjectURL; click Close; expect it called once per created URL */
  });
});
```

Write the two summarised bodies out fully, in the same style as the third test.

- [ ] **Step 3: Implement**

`endpoints.ts`:

```ts
/** The real statement, shown in the browser only. Opening is audited with the reason, emails the
 *  Super Admins and tells the customer; there is no download. Page images ride the authenticated
 *  axios instance because an <img src> cannot carry the Bearer token. */
export const adminOriginalViewApi = {
  openImport: (jobId: string, reason: string) =>
    api.post<OriginalViewOpened>(`/admin/held-imports/${jobId}/original-view`, { reason }).then((r) => r.data),
  openTrust: (heldId: string, reason: string) =>
    api.post<OriginalViewOpened>(`/admin/held-statements/${heldId}/original-view`, { reason }).then((r) => r.data),
  pageBlob: (viewId: string, n: number) =>
    api.get(`/admin/original-views/${viewId}/pages/${n}`, { responseType: 'blob' }).then((r) => r.data as Blob),
  csv: (viewId: string) =>
    api.get<{ lines: string[] }>(`/admin/original-views/${viewId}/csv`).then((r) => r.data.lines),
};
```

Take `OriginalViewOpened` from the regenerated types. Match how `api` unwraps `ApiResponse` in
neighbouring calls (`.then((r) => r.data)`).

`OriginalStatementViewer`: use the portal's existing modal component (the one `HeldImports`
uses for its resolve dialog).
- Step one shows:
  - a labelled `Reason` textarea, 15 to 500 characters, with a counter;
  - the text "Only Admins can do this. Super Admins will be emailed and the reason is recorded.
    The customer will be told the date and that it was to fix a reading problem.";
  - an "Open statement" button.
- Step two shows:
  - for a PDF: pages fetched one after another, each as
    `<img alt={`Page ${n} of ${count}`} draggable={false} src={objectUrl} />` inside a container
    with `onContextMenu={(e) => e.preventDefault()}` and `user-select: none`;
  - for a CSV: the lines in a `<pre>`;
  - a line under the title: "Open until {time}. Nothing here can be downloaded."
- Revoke every object URL on unmount.
- Map a 404 to "This view has ended. Open the statement again.", a 409 to the server's message,
  and a 503 to "The server is busy. Try again in a minute."

Pages: replace the download button with a "View original" button that opens the viewer. Keep the
button's existing visibility condition (it's already limited to Admin and Super Admin). Remove the
now-unused `downloadBlob` imports.

- [ ] **Step 4: Run them**

Run (Node 22, in `admin-portal/`): `npm run lint`, `npm run build`, `npx vitest run`. Expected:
all pass. Then check it in a browser: run this worktree's admin portal against a stub API that
returns two generated PNGs, take a screenshot of the reason step and of the pages step in light and
dark, at desktop and phone width, and check there are no console errors and no horizontal scroll.

- [ ] **Step 5: Commit**

```bash
git add backend/openapi/openapi.json admin-portal/src frontend/src/api/generated-types.ts mobile/src/api/generated-types.ts
git commit -m "feat(admin-portal): view a held statement's original pages with a reason, instead of downloading it"
```

---

### Task 9: Verification and PR

- [ ] **Step 1: Full runs on the final code**
  - Backend: `./mvnw verify`.
  - `admin-portal/` and `frontend/` under Node 22: `npm run lint`, `npm run build`,
    `npx vitest run`.
  - `mobile/` under Node 22: `npm run typecheck`, because its generated types changed.

  Record the counts.

- [ ] **Step 2: Gaps against the spec**

For each item, name the test or command that proves it:
1. Both download endpoints are gone (Task 6, `theOldDownloadEndpointsAreGone`).
2. A reason is required (Task 4 and Task 6).
3. The Super Admin email (Task 5 and Task 6).
4. The audit entry, written before the read (Task 3).
5. The customer notice: wording, moment, date and no name (Task 5, Task 6, Task 7).
6. Browser only: no attachment and no-store (Task 6, Task 8).
7. Support roles are refused (Task 6).

- [ ] **Step 3: Look for leaks yourself**

Run `git diff origin/main --stat`, then read every new log line, exception message and audit
detail in the diff. None may carry statement text, the file name, the customer's name or email.
The Super Admin email may carry the viewer's email and the typed reason only.

- [ ] **Step 4: Check merge state, push and open the PR**

Run `git fetch origin && git log --oneline origin/main..HEAD`, then check the migration numbers
are still free on `origin/main`. Push and open the PR.

The PR body covers:
- what changed;
- before and after (download → view);
- the exact customer wording;
- what was verified, with the counts;
- what is deferred: a mobile import-history line (waits for an app release); and whether the
  trust queue's `staged-rows` endpoint, which also shows statement rows, should get the same
  reason and notice. Raise the second as a question for the owner, not a change.

The repository is public, so CI skips integration tests on PRs. Say that the local `verify` covers
them.

- [ ] **Step 5: Check after merge**

Once the owner merges: `gh pr view <n> --json state,mergedAt,headRefOid`. Compare `headRefOid`
with your last pushed commit.
