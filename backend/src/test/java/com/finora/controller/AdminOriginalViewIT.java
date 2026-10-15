package com.finora.controller;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.AuditLog;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.Permission;
import com.finora.entity.Role;
import com.finora.entity.User;
import com.finora.exception.ErrorCode;
import com.finora.imports.original.StatementOriginalViewRepository;
import com.finora.imports.storage.ContentAddress;
import com.finora.imports.storage.StatementStorage;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.repository.NotificationRepository;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.PermissionRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.RoleRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.AdminHeldImportService;
import com.finora.service.EmailMessage;
import com.finora.service.EmailProvider;
import com.finora.service.EmailResult;
import com.finora.service.ProviderType;
import com.finora.testsupport.TestSessions;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The original-view endpoints end to end: who may open a held statement, that a reason is required
 * before anything happens, that pages come back as no-store PNG images for the opener alone and
 * stop once the item is released, and that a successful opening -- and only one -- emails the Super
 * Admins and tells the customer after it commits. Also the removed downloads: they answer nothing.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-admin-original-view-it"
})
class AdminOriginalViewIT extends AbstractIntegrationTest {

    private static final String REASON = "Rows on page 2 are missing from the preview";
    /** The customer's own file name. It must never reach a response or the Super Admin email. */
    private static final String CUSTOMER_FILE_NAME = "Asha Verma statement.pdf"; // synthetic-ok: invented name

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private HeldStatementRepository heldStatementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private StatementOriginalViewRepository views;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private AdminHeldImportService heldImports;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private StatementStorage storage;
    @MockitoBean private EmailProvider emailProvider;

    @BeforeEach
    void emailsSucceed() {
        when(emailProvider.send(any())).thenReturn(EmailResult.success(ProviderType.RESEND, "id-1"));
    }

    static byte[] pdfWithPages(int pages) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream s = new PDPageContentStream(doc, page)) {
                    s.beginText();
                    s.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    s.newLineAtOffset(72, 720);
                    s.showText("Synthetic page " + (i + 1) + " " + UUID.randomUUID());
                    s.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private User createUser(String role) {
        User user = new User();
        user.setEmail("original-view-http-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Original View HTTP IT User");
        user.setRole(role);
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    /** A support-portal account holding one queue permission through a real RBAC role, and no ADMIN
     *  role, as {@code AdminBlankedCopyIT} builds it. */
    private User createSupportUser(String permission) {
        Permission p = permissionRepository.findAll().stream()
                .filter(x -> x.getName().equals(permission)).findFirst().orElseThrow();
        Role role = new Role();
        role.setName("SUPPORT_OV_IT_" + UUID.randomUUID().toString().substring(0, 8));
        role.setDescription("Support role for the original-view IT");
        role.getPermissions().add(p);
        roleRepository.save(role);
        return createUser(role.getName());
    }

    /** ROLE_ADMIN from the legacy role string, but no queue permission: the account scope is the
     *  default (user), which withholds the ADMIN role's permissions. Tells the AND from an OR. */
    private User createAdminRoleWithoutPermissions() {
        User user = new User();
        user.setEmail("original-view-http-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Original View HTTP IT User");
        user.setRole("ADMIN");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ImportJob heldJob(byte[] content, String sourceFormat) {
        User owner = createUser("USER");
        ContentAddress address = storage.store(content);
        ImportJob job = new ImportJob(owner.getId(), CUSTOMER_FILE_NAME, address.hash(), address.key(), sourceFormat);
        job.markClaimed("worker", Instant.now());
        job.markClaimed("worker", Instant.now());
        job.recordFailure("IllegalStateException: no header row", "IllegalStateException",
                ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT, Instant.now());
        job.holdForReview("IllegalStateException", Instant.now());
        return importJobRepository.save(job);
    }

    private ImportJob heldJobWithPdf(byte[] pdf) {
        return heldJob(pdf, "PDF");
    }

    private HeldStatement seedHold(String heldId) {
        User owner = createUser("USER");
        ContentAddress address = storage.store(pdfWithPages(2));
        ImportJob job = new ImportJob(owner.getId(), CUSTOMER_FILE_NAME, address.hash(), address.key(), "PDF");
        job.markClaimed("worker", Instant.now());
        UUID sessionId = UUID.randomUUID();
        job.holdForTrustReview(sessionId, null, Instant.now());
        importJobRepository.save(job);
        HeldStatement held = heldStatementRepository.save(new HeldStatement(
                heldId, job.getId(), owner.getId(), job.getObjectKey(),
                "Printed and parsed transaction count disagree (ROW_GROUPING)"));
        job.holdForTrustReview(sessionId, held.getId(), Instant.now());
        importJobRepository.save(job);
        return held;
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> open(String path, Object reason, User caller) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("reason", reason);
        HttpHeaders headers = caller == null ? jsonOnly() : bearerFor(caller);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private static HttpHeaders jsonOnly() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> openImport(ImportJob job, User caller) {
        return open("/api/v1/admin/held-imports/" + job.getId() + "/original-view", REASON, caller);
    }

    @SuppressWarnings("rawtypes")
    private static Map<?, ?> data(ResponseEntity<Map> response) {
        return (Map<?, ?>) response.getBody().get("data");
    }

    private ResponseEntity<byte[]> page(Object viewId, int n, User caller) {
        return restTemplate.exchange("/api/v1/admin/original-views/" + viewId + "/pages/" + n, HttpMethod.GET,
                new HttpEntity<>(bearerFor(caller)), byte[].class);
    }

    private List<String> actionsOn(UUID entityId) {
        return auditLogRepository.findByEntityIdOrderByCreatedAtAsc(entityId).stream().map(AuditLog::getAction).toList();
    }

    private List<Notification> openedNoticesFor(UUID userId) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(n -> n.getType() == NotificationType.IMPORT_STATEMENT_OPENED_FOR_FIX).toList();
    }

    private List<EmailMessage> sent() {
        if (mockingDetails(emailProvider).getInvocations().stream().noneMatch(i -> i.getMethod().getName().equals("send"))) {
            return List.of();
        }
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailProvider, atLeast(1)).send(captor.capture());
        return captor.getAllValues();
    }

    // ------------------------------------------------------------------------- who may open

    @Test
    void openingRefusesAnUnauthenticatedCaller() {
        ImportJob job = heldJobWithPdf(pdfWithPages(1));

        assertThat(openImport(job, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(actionsOn(job.getId())).isEmpty();
    }

    @Test
    void openingRefusesAPlainUser() {
        ImportJob job = heldJobWithPdf(pdfWithPages(1));

        assertThat(openImport(job, createUser("USER")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(actionsOn(job.getId())).isEmpty();
    }

    @Test
    void openingRefusesASupportRoleHoldingOnlyTheQueuePermission() {
        ImportJob job = heldJobWithPdf(pdfWithPages(1));
        HeldStatement held = seedHold("HLD-2026-910001");

        assertThat(openImport(job, createSupportUser("IMPORT_TRIAGE_MANAGE")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(open("/api/v1/admin/held-statements/HLD-2026-910001/original-view", REASON,
                createSupportUser("TRUST_REVIEW_MANAGE")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(actionsOn(job.getId())).isEmpty();
        assertThat(actionsOn(held.getId())).isEmpty();
    }

    @Test
    void openingRefusesAnAdminRoleAccountWithoutTheQueuePermission() {
        ImportJob job = heldJobWithPdf(pdfWithPages(1));
        seedHold("HLD-2026-910002");
        User adminRoleOnly = createAdminRoleWithoutPermissions();

        assertThat(openImport(job, adminRoleOnly).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(open("/api/v1/admin/held-statements/HLD-2026-910002/original-view", REASON, adminRoleOnly)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aBlankReasonIsRefusedBeforeAnything() {
        ImportJob job = heldJobWithPdf(pdfWithPages(1));
        User admin = createUser("ADMIN");

        for (Object reason : new Object[] {"     ", null, "too short"}) {
            var response = open("/api/v1/admin/held-imports/" + job.getId() + "/original-view", reason, admin);
            assertThat(response.getStatusCode()).as(String.valueOf(reason)).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().toString()).contains("Type a reason of 15 to 500 characters.");
        }
        assertThat(actionsOn(job.getId())).doesNotContain("HELD_IMPORT_ORIGINAL_VIEWED");
        assertThat(views.findByJobIdOrderByOpenedAtAsc(job.getId())).isEmpty();
        assertThat(openedNoticesFor(job.getUserId())).isEmpty();
    }

    @Test
    void anUnknownJobIsNotFoundAndAJobNotHeldIsAConflict() {
        User admin = createUser("ADMIN");
        assertThat(open("/api/v1/admin/held-imports/" + UUID.randomUUID() + "/original-view", REASON, admin)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(open("/api/v1/admin/held-statements/HLD-2026-999998/original-view", REASON, admin)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        User owner = createUser("USER");
        ContentAddress address = storage.store(pdfWithPages(1));
        ImportJob queued = importJobRepository.save(
                new ImportJob(owner.getId(), CUSTOMER_FILE_NAME, address.hash(), address.key(), "PDF"));
        assertThat(openImport(queued, admin).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(actionsOn(queued.getId())).isEmpty();
    }

    // ------------------------------------------------------------------------- what an opening shows

    @Test
    void anAdminOpensAPdfAndGetsItsPagesAsImages() throws IOException {
        ImportJob job = heldJobWithPdf(pdfWithPages(2));
        User admin = createUser("ADMIN");

        var opened = openImport(job, admin);
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(opened.getHeaders().getCacheControl()).contains("no-store");
        assertThat(opened.getBody().toString()).doesNotContain(CUSTOMER_FILE_NAME);
        Map<?, ?> data = data(opened);
        assertThat(data.get("pageCount")).isEqualTo(2);
        assertThat(data.get("format")).isEqualTo("PDF");
        assertThat(data.get("expiresAt")).isNotNull();

        for (int n = 1; n <= 2; n++) {
            ResponseEntity<byte[]> page = page(data.get("viewId"), n, admin);
            assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(page.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
            assertThat(page.getHeaders().getCacheControl()).contains("no-store");
            assertThat(page.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).isNull();
            assertThat(page.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(ImageIO.read(new ByteArrayInputStream(page.getBody())).getWidth()).isBetween(800, 1300);
        }
        assertThat(actionsOn(job.getId())).containsOnlyOnce("HELD_IMPORT_ORIGINAL_VIEWED");
        assertThat(views.findByJobIdOrderByOpenedAtAsc(job.getId())).singleElement()
                .satisfies(v -> assertThat(v.getViewerId()).isEqualTo(admin.getId()));
        // The reason is on the audit entry, and only there (V271).
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId()))
                .filteredOn(e -> e.getAction().equals("HELD_IMPORT_ORIGINAL_VIEWED"))
                .singleElement()
                .satisfies(e -> assertThat(e.getMetadata()).containsEntry("reason", REASON));
    }

    @Test
    void aSuperAdminOpensToo() {
        ImportJob job = heldJobWithPdf(pdfWithPages(1));
        User superAdmin = createUser("SUPER_ADMIN");

        var opened = openImport(job, superAdmin);

        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page(data(opened).get("viewId"), 1, superAdmin).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aPageOutOfRangeIsNotFound() {
        ImportJob job = heldJobWithPdf(pdfWithPages(2));
        User admin = createUser("ADMIN");
        Object viewId = data(openImport(job, admin)).get("viewId");

        assertThat(page(viewId, 3, admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(page(viewId, 0, admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anotherAdminCannotUseTheView() {
        ImportJob job = heldJobWithPdf(pdfWithPages(1));
        User admin = createUser("ADMIN");
        Object viewId = data(openImport(job, admin)).get("viewId");

        assertThat(page(viewId, 1, createUser("ADMIN")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(page(viewId, 1, createUser("SUPER_ADMIN")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(page(UUID.randomUUID(), 1, admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(page(viewId, 1, createUser("USER")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aPageOfAReleasedItemIsRefused() {
        ImportJob job = heldJobWithPdf(pdfWithPages(2));
        User admin = createUser("ADMIN");
        Object viewId = data(openImport(job, admin)).get("viewId");
        assertThat(page(viewId, 1, admin).getStatusCode()).isEqualTo(HttpStatus.OK);

        heldImports.resolve(createUser("ADMIN").getId(), job.getId(), "We fixed the reader; please re-upload.");

        ResponseEntity<byte[]> refused = page(viewId, 2, admin);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // The viewer shows the server's own words for a 409, so the JSON message must survive an
        // endpoint that otherwise only produces image/png.
        assertThat(new String(refused.getBody(), StandardCharsets.UTF_8))
                .contains("Only a held import can be opened");
    }

    @Test
    void aCsvHoldIsShownAsLines() {
        ImportJob job = heldJob("Date,Amount\n01/10/2026,100.00\n".getBytes(StandardCharsets.UTF_8), "CSV");
        User admin = createUser("ADMIN");

        var opened = openImport(job, admin);
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(opened).get("format")).isEqualTo("CSV");
        assertThat(data(opened).get("pageCount")).isEqualTo(0);

        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> csv = restTemplate.exchange("/api/v1/admin/original-views/" + data(opened).get("viewId") + "/csv",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), Map.class);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csv.getHeaders().getCacheControl()).contains("no-store");
        assertThat(data(csv).get("lines")).isEqualTo(List.of("Date,Amount", "01/10/2026,100.00"));
        assertThat(page(data(opened).get("viewId"), 1, admin).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void theTrustQueueOpensToo() {
        HeldStatement held = seedHold("HLD-2026-910003");
        User admin = createUser("ADMIN");

        var opened = open("/api/v1/admin/held-statements/HLD-2026-910003/original-view", REASON, admin);

        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(opened).get("pageCount")).isEqualTo(2);
        assertThat(page(data(opened).get("viewId"), 2, admin).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(held.getId())).anySatisfy(e -> {
            assertThat(e.getAction()).isEqualTo("TRUST_REVIEW_ORIGINAL_VIEWED");
            assertThat(e.getMetadata()).containsEntry("heldId", "HLD-2026-910003").containsEntry("reason", REASON);
        });
        // The trust hold's customer is told too, on the job behind the hold.
        assertThat(openedNoticesFor(held.getUserId())).hasSize(2)
                .allSatisfy(n -> assertThat(n.getCategory())
                        .isEqualTo(com.finora.notification.domain.NotificationCategory.SECURITY));

        held.reject(admin.getId(), Instant.now());
        heldStatementRepository.save(held);
        assertThat(page(data(opened).get("viewId"), 1, admin).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void theOldDownloadEndpointsAreGone() {
        ImportJob job = heldJobWithPdf(pdfWithPages(1));
        seedHold("HLD-2026-910004");
        User admin = createUser("ADMIN");

        for (String path : List.of("/api/v1/admin/held-imports/" + job.getId() + "/document",
                "/api/v1/admin/held-statements/HLD-2026-910004/document")) {
            ResponseEntity<byte[]> response = restTemplate.exchange(path, HttpMethod.GET,
                    new HttpEntity<>(bearerFor(admin)), byte[].class);
            assertThat(response.getStatusCode()).as(path).isIn(HttpStatus.NOT_FOUND, HttpStatus.METHOD_NOT_ALLOWED);
        }
        assertThat(actionsOn(job.getId())).isEmpty();
    }

    // ------------------------------------------------------------------------- who is told

    @Test
    void anOpeningTellsTheCustomerAndEmailsTheSuperAdmins() {
        User superAdmin = createUser("SUPER_ADMIN");
        ImportJob job = heldJobWithPdf(pdfWithPages(1));
        User admin = createUser("ADMIN");
        User owner = userRepository.findById(job.getUserId()).orElseThrow();

        assertThat(openImport(job, admin).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Sent after commit, off the request: wait for it.
        verify(emailProvider, timeout(10_000)).send(argThat(m -> m != null && superAdmin.getEmail().equals(m.to())));
        List<EmailMessage> toSuperAdmin = sent().stream().filter(m -> m.to().equals(superAdmin.getEmail())).toList();
        assertThat(toSuperAdmin).singleElement().satisfies(m -> {
            assertThat(m.subject()).isEqualTo("A held statement was opened — " + job.getId());
            assertThat(m.html()).contains(admin.getEmail()).contains(REASON)
                    .doesNotContain(CUSTOMER_FILE_NAME).doesNotContain(owner.getEmail());
        });
        assertThat(openedNoticesFor(owner.getId())).hasSize(2)
                .allSatisfy(n -> assertThat(n.getMessage()).contains("our team").contains("fix a")
                        .doesNotContain(admin.getEmail()));
    }

    /**
     * The Super Admin email is sent off the request: the opening runs under one of the import slots
     * customers' uploads share, and an email provider can take seconds per recipient. The email still
     * goes, afterwards.
     */
    @Test
    void aSlowEmailProviderDoesNotHoldTheOpening() {
        // The suite shares one database, and other classes leave active Super Admins in it; every
        // one of them is emailed, one after another, in no set order. Twenty more here make that
        // explicit.
        for (int i = 0; i < 20; i++) createUser("SUPER_ADMIN");
        User superAdmin = createUser("SUPER_ADMIN");
        ImportJob job = heldJobWithPdf(pdfWithPages(1));
        User admin = createUser("ADMIN");
        // Only this test's Super Admin is slow. Were every send slow, how long the email took to
        // arrive would depend on where this Super Admin falls in an unordered list of them --
        // which is what failed main's CI on 2026-10-15 (5 others were emailed first, 3 s each).
        when(emailProvider.send(any())).thenAnswer(invocation -> {
            EmailMessage message = invocation.getArgument(0);
            if (message != null && superAdmin.getEmail().equals(message.to())) Thread.sleep(3000);
            return EmailResult.success(ProviderType.RESEND, "slow");
        });

        long started = System.nanoTime();
        assertThat(openImport(job, admin).getStatusCode()).isEqualTo(HttpStatus.OK);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMs).as("the opening waited for the email").isLessThan(2500);
        verify(emailProvider, timeout(15_000)).send(argThat(m -> m != null && superAdmin.getEmail().equals(m.to())));
    }

    /** Review focus 4: the same admin opens the same item twice in a day. Two view rows, two audit
     *  entries, two Super Admin emails -- one customer notice (a push and an email). */
    @Test
    void twoOpeningsOnOneDayAreBothRecordedButTellTheCustomerOnce() {
        User superAdmin = createUser("SUPER_ADMIN");
        ImportJob job = heldJobWithPdf(pdfWithPages(1));
        User admin = createUser("ADMIN");

        assertThat(openImport(job, admin).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(openImport(job, admin).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(views.findByJobIdOrderByOpenedAtAsc(job.getId())).hasSize(2);
        assertThat(actionsOn(job.getId())).filteredOn("HELD_IMPORT_ORIGINAL_VIEWED"::equals).hasSize(2);
        verify(emailProvider, timeout(10_000).times(2))
                .send(argThat(m -> m != null && superAdmin.getEmail().equals(m.to())));
        assertThat(openedNoticesFor(job.getUserId())).extracting(Notification::getChannel)
                .containsExactlyInAnyOrder(com.finora.notification.domain.NotificationChannel.PUSH,
                        com.finora.notification.domain.NotificationChannel.EMAIL);
    }

    /** Admins see who opened a held statement, and when, on the hold itself: the trust page's
     *  History and the held-import panel. Never the reason, which stays on the audit entry. */
    @Test
    void openingsShowOnTheHoldForAdmins() throws Exception {
        HeldStatement held = seedHold("HLD-2026-910005");
        ImportJob job = heldJobWithPdf(pdfWithPages(1));
        User admin = createUser("ADMIN");
        assertThat(open("/api/v1/admin/held-statements/HLD-2026-910005/original-view", REASON, admin)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(openImport(job, admin).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> trust = restTemplate.exchange("/api/v1/admin/held-statements/HLD-2026-910005",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);
        com.fasterxml.jackson.databind.JsonNode timeline = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(trust.getBody()).get("data").get("timeline");
        assertThat(timeline.findValuesAsText("eventType")).contains("ORIGINAL_VIEWED");
        assertThat(timeline.toString()).contains("Opened by " + admin.getEmail()).doesNotContain(REASON);

        ResponseEntity<String> imported = restTemplate.exchange("/api/v1/admin/held-imports/" + job.getId(),
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);
        com.fasterxml.jackson.databind.JsonNode openings = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(imported.getBody()).get("data").get("originalOpenings");
        assertThat(openings).isNotNull();
        assertThat(openings.size()).isEqualTo(1);
        assertThat(openings.get(0).get("openedBy").asText()).isEqualTo(admin.getEmail());
        assertThat(openings.get(0).get("openedAt").asText()).isNotBlank();
        assertThat(imported.getBody()).doesNotContain(REASON);
    }

    @Test
    void aFailedOpeningTellsNoOne() {
        User superAdmin = createUser("SUPER_ADMIN");
        ImportJob job = heldJobWithPdf(pdfWithPages(1));
        storage.delete(job.getObjectKey());
        User admin = createUser("ADMIN");

        assertThat(openImport(job, admin).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(actionsOn(job.getId())).contains("HELD_IMPORT_ORIGINAL_VIEWED");
        assertThat(views.findByJobIdOrderByOpenedAtAsc(job.getId())).isEmpty();
        assertThat(openedNoticesFor(job.getUserId())).isEmpty();
        // Long enough for the off-request email to have gone, had there been one to send.
        verify(emailProvider, after(1_500).never()).send(argThat(m -> m != null && superAdmin.getEmail().equals(m.to())));
    }

    @Test
    void aPdfThatCannotBeRenderedTellsNoOneButStaysAudited() {
        User superAdmin = createUser("SUPER_ADMIN");
        ImportJob job = heldJob("not a pdf at all".getBytes(StandardCharsets.UTF_8), "PDF");
        User admin = createUser("ADMIN");

        var response = openImport(job, admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().toString()).doesNotContain("not a pdf at all");
        assertThat(actionsOn(job.getId())).contains("HELD_IMPORT_ORIGINAL_VIEWED");
        assertThat(views.findByJobIdOrderByOpenedAtAsc(job.getId())).isEmpty();
        assertThat(openedNoticesFor(job.getUserId())).isEmpty();
        // Long enough for the off-request email to have gone, had there been one to send.
        verify(emailProvider, after(1_500).never()).send(argThat(m -> m != null && superAdmin.getEmail().equals(m.to())));
    }
}
