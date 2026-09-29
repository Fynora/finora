package com.finora.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.VerificationFinding;
import com.finora.dto.ImportDto.VerificationReport;
import com.finora.entity.RegisteredLayout;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.RegisteredLayoutRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The layout review queue and layout profiles (V243) against a real PostgreSQL: the staging upsert
 * and its {@code xmax} new-row test, the row lock that decides whether an alert goes out, the
 * acknowledged-reason rule, version numbering under the profile lock, and the HTTP permission gates.
 * Every fingerprint is random -- the IT suite shares one database, and the registry is global.
 */
class LayoutReviewAndProfilesIT extends AbstractIntegrationTest {

    @Autowired private LayoutReviewService reviewService;
    @Autowired private LayoutCurationService curationService;
    @Autowired private LayoutRegistryService registryService;
    @Autowired private RegisteredLayoutRepository layoutRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private ImportService importService;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean private LayoutReviewAlertService alertService;
    private final ObjectMapper mapper = new ObjectMapper();

    private static String fingerprint() {
        return "FP-T-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static StagedRow row(String description) {
        return new StagedRow(LocalDate.of(2026, 1, 15), description, new BigDecimal("100.00"), "EXPENSE",
                "Other", "default", null, false, null, null);
    }

    private static List<StagedRow> rows(int described, int blank) {
        List<StagedRow> rows = new ArrayList<>();
        for (int i = 0; i < described; i++) rows.add(row("SAMPLE STORE " + i));
        for (int i = 0; i < blank; i++) rows.add(row(""));
        return rows;
    }

    private static List<VerificationReport> report(String outcome) {
        return report("SOME_RULE", outcome);
    }

    private static List<VerificationReport> report(String rule, String outcome) {
        return List.of(new VerificationReport(List.of(new VerificationFinding(rule, outcome, Map.of())),
                false, null, null));
    }

    private UUID adminId() {
        return createUser("ADMIN").getId();
    }

    private RegisteredLayout layout(String fingerprint) {
        return layoutRepository.findByFingerprint(fingerprint).orElseThrow();
    }

    // ------------------------------------------------------------------ review flag at staging

    @Test
    void aFirstStagingRegistersTheLayoutFlagsItAsNewAndAlertsOnce() {
        String fp = fingerprint();

        reviewService.onStaged(fp, "PDF", rows(3, 0), report("VERIFIED"), "SA-TEST-1", null);
        reviewService.onStaged(fp, "PDF", rows(3, 0), report("VERIFIED"), "SA-TEST-2", null);

        RegisteredLayout layout = layout(fp);
        assertThat(layout.getStagingCount()).isEqualTo(2);
        assertThat(layout.getObservationCount()).isZero();
        assertThat(layout.isNeedsReview()).isTrue();
        assertThat(layout.getReviewReasons()).containsExactly("NEW_LAYOUT");
        assertThat(layout.getReviewAnalysisReference()).isEqualTo("SA-TEST-1");
        verify(alertService, timeout(5000).times(1)).alertLayoutNeedsReview(eq(fp), eq(List.of("NEW_LAYOUT")), eq("SA-TEST-1"));
    }

    @Test
    void aKnownLayoutThatStagesCleanlyIsNotFlagged() {
        String fp = fingerprint();
        registryService.observe(fp, "PDF", null); // a confirmed import registered it earlier

        reviewService.onStaged(fp, "PDF", rows(4, 1), report("VERIFIED"), "SA-TEST-3", null);

        RegisteredLayout layout = layout(fp);
        assertThat(layout.isNeedsReview()).isFalse();
        assertThat(layout.getStagingCount()).isEqualTo(1);
        assertThat(layout.getObservationCount()).isEqualTo(1);
        verify(alertService, after(500).never()).alertLayoutNeedsReview(eq(fp), any(), any());
    }

    @Test
    void blankDescriptionsFlagOnlyWhenMoreThanHalfTheRowsAreBlank() {
        String exactlyHalf = fingerprint();
        String moreThanHalf = fingerprint();
        registryService.observe(exactlyHalf, "PDF", null);
        registryService.observe(moreThanHalf, "PDF", null);

        reviewService.onStaged(exactlyHalf, "PDF", rows(2, 2), report("VERIFIED"), "SA-TEST-4", null);
        reviewService.onStaged(moreThanHalf, "PDF", rows(2, 3), report("VERIFIED"), "SA-TEST-5", null);

        assertThat(layout(exactlyHalf).isNeedsReview()).isFalse();
        assertThat(layout(moreThanHalf).getReviewReasons()).containsExactly("BLANK_DESCRIPTIONS");
    }

    @Test
    void aResolvedReasonDoesNotReflag_butANewReasonDoesAndAlertsAgain() {
        String fp = fingerprint();
        registryService.observe(fp, "PDF", null);
        reviewService.onStaged(fp, "PDF", rows(3, 0), report("WARNING"), "SA-TEST-6", null);
        assertThat(layout(fp).getReviewReasons()).containsExactly("VERIFICATION_NOT_PASSED:SOME_RULE");

        curationService.resolveReview(adminId(), fp);
        clearInvocations(alertService);

        reviewService.onStaged(fp, "PDF", rows(3, 0), report("WARNING"), "SA-TEST-7", null);
        assertThat(layout(fp).isNeedsReview()).isFalse();
        verify(alertService, after(500).never()).alertLayoutNeedsReview(anyString(), any(), any());

        reviewService.onStaged(fp, "PDF", rows(1, 3), report("WARNING"), "SA-TEST-8", null);
        RegisteredLayout layout = layout(fp);
        assertThat(layout.isNeedsReview()).isTrue();
        assertThat(layout.getReviewReasons()).containsExactly("BLANK_DESCRIPTIONS");
        assertThat(layout.getAcknowledgedReasons()).containsExactly("VERIFICATION_NOT_PASSED:SOME_RULE");
        verify(alertService, timeout(5000).times(1)).alertLayoutNeedsReview(eq(fp), eq(List.of("BLANK_DESCRIPTIONS")), eq("SA-TEST-8"));
    }

    /** Acknowledging one rule's routine warning must not silence a different rule failing later. */
    @Test
    void acknowledgingOneRulesWarningDoesNotSilenceADifferentRule() {
        String fp = fingerprint();
        registryService.observe(fp, "PDF", null);
        reviewService.onStaged(fp, "PDF", rows(3, 0), report("TOTALS_RULE", "WARNING"), "SA-TEST-20", null);
        curationService.resolveReview(adminId(), fp);
        clearInvocations(alertService);

        reviewService.onStaged(fp, "PDF", rows(3, 0), report("TOTALS_RULE", "WARNING"), "SA-TEST-21", null);
        assertThat(layout(fp).isNeedsReview()).isFalse();

        reviewService.onStaged(fp, "PDF", rows(3, 0), report("CHAIN_RULE", "FAILED"), "SA-TEST-22", null);
        assertThat(layout(fp).getReviewReasons()).containsExactly("VERIFICATION_NOT_PASSED:CHAIN_RULE");
        verify(alertService, timeout(5000).times(1)).alertLayoutNeedsReview(eq(fp), any(), eq("SA-TEST-22"));
    }

    @Test
    void aDocumentWithNoRecognisedHeadersIsNeverRegisteredOrFlagged() {
        String headerless = new DocumentContext("PDF", "any").buildFingerprint();
        long before = layoutRepository.findByFingerprint(headerless).map(RegisteredLayout::getStagingCount).orElse(0L);

        reviewService.onStagingFailed(headerless, "PDF", "SA-TEST-23", new IllegalStateException("parser broke"));

        assertThat(layoutRepository.findByFingerprint(headerless).map(RegisteredLayout::getStagingCount).orElse(0L))
                .isEqualTo(before);
        verify(alertService, after(500).never()).alertLayoutNeedsReview(eq(headerless), any(), any());
    }

    @Test
    void aSecondReasonWhileStillFlaggedIsAddedWithoutASecondAlert() {
        String fp = fingerprint();
        reviewService.onStaged(fp, "PDF", rows(3, 0), report("VERIFIED"), "SA-TEST-9", null);
        reviewService.onStaged(fp, "PDF", rows(3, 0), report("FAILED"), "SA-TEST-10", null);

        assertThat(layout(fp).getReviewReasons()).containsExactly("NEW_LAYOUT", "VERIFICATION_NOT_PASSED:SOME_RULE");
        verify(alertService, timeout(5000).times(1)).alertLayoutNeedsReview(eq(fp), any(), any());
    }

    @Test
    void aFailedStagingFlagsTheLayout_andNoFingerprintIsANoOp() {
        String fp = fingerprint();
        registryService.observe(fp, "PDF", null);

        reviewService.onStagingFailed(fp, "PDF", "SA-TEST-11", new IllegalStateException("parser broke"));
        reviewService.onStagingFailed(null, "PDF", "SA-TEST-12", new IllegalStateException("parser broke"));

        assertThat(layout(fp).getReviewReasons()).containsExactly("STAGING_FAILED");
    }

    @Test
    void aDeliberateRefusalRegistersTheLayoutButDoesNotFlagIt() {
        String fp = fingerprint();
        registryService.observe(fp, "PDF", null);

        reviewService.onStagingFailed(fp, "PDF", "SA-TEST-30",
                new ApiException(com.finora.exception.ErrorCode.IMPORT_NO_ACTIVITY_IN_PERIOD));

        assertThat(layout(fp).isNeedsReview()).isFalse();
        assertThat(layout(fp).getStagingCount()).isEqualTo(1);
    }

    @Test
    void aConfirmedImportAfterStagingCountsAsAnObservationAndLeavesTheFlagAlone() {
        String fp = fingerprint();
        reviewService.onStaged(fp, "PDF", rows(3, 0), report("VERIFIED"), "SA-TEST-13", null);

        registryService.observe(fp, "PDF", null);

        RegisteredLayout layout = layout(fp);
        assertThat(layout.getObservationCount()).isEqualTo(1);
        assertThat(layout.getStagingCount()).isEqualTo(1);
        assertThat(layout.isNeedsReview()).isTrue();
    }

    @Test
    void resolvingALayoutThatIsNotFlaggedIsAConflict() {
        String fp = fingerprint();
        registryService.observe(fp, "PDF", null);

        assertThatThrownBy(() -> curationService.resolveReview(adminId(), fp))
                .isInstanceOf(ApiException.class).hasMessageContaining("not waiting for review");
    }

    // ------------------------------------------------------------------ profiles and versions

    @Test
    void layoutsJoinAProfileAsConsecutiveVersions_relinkingIsANoOp_andMovingTakesTheNextVersion() {
        String older = fingerprint();
        String newer = fingerprint();
        String other = fingerprint();
        for (String fp : List.of(older, newer, other)) registryService.observe(fp, "PDF", null);
        var kotak = curationService.createProfile(adminId(), "Test Kotak CC " + UUID.randomUUID());
        var axis = curationService.createProfile(adminId(), "Test Axis CC " + UUID.randomUUID());

        assertThat(curationService.linkToProfile(adminId(), older, kotak.id()).profileVersion()).isEqualTo(1);
        assertThat(curationService.linkToProfile(adminId(), newer, kotak.id()).profileVersion()).isEqualTo(2);
        assertThat(curationService.linkToProfile(adminId(), newer, kotak.id()).profileVersion()).isEqualTo(2);
        curationService.linkToProfile(adminId(), other, axis.id());

        var moved = curationService.linkToProfile(adminId(), other, kotak.id());
        assertThat(moved.profileVersion()).isEqualTo(3);
        assertThat(moved.profileName()).isEqualTo(kotak.name());

        curationService.unlinkFromProfile(adminId(), newer);
        var view = curationService.profiles().stream().filter(p -> p.id().equals(kotak.id())).findFirst().orElseThrow();
        assertThat(view.versions()).extracting(LayoutCurationService.RegistryEntry::fingerprint).containsExactly(older, other);
        assertThat(view.versions()).extracting(LayoutCurationService.RegistryEntry::profileVersion).containsExactly(1, 3);
    }

    @Test
    void profileNamesAreUniqueIgnoringCase() {
        String name = "Test Unique Profile " + UUID.randomUUID();
        curationService.createProfile(adminId(), name);

        assertThatThrownBy(() -> curationService.createProfile(adminId(), name.toUpperCase()))
                .isInstanceOf(ApiException.class).hasMessageContaining("already exists");
        assertThatThrownBy(() -> curationService.createProfile(adminId(), "  "))
                .isInstanceOf(ApiException.class);
    }

    // ------------------------------------------------------------------ HTTP gates

    private User createUser(String role) {
        User user = new User();
        user.setEmail("layout-review-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Layout Review IT User");
        user.setRole(role);
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void anAdminCanReadTheQueueRenameAndResolve_andEveryWriteIsAudited() throws Exception {
        User admin = createUser("ADMIN");
        String fp = fingerprint();
        reviewService.onStaged(fp, "PDF", rows(1, 3), report("VERIFIED"), "SA-TEST-14", null);
        String base = "/api/v1/admin/imports/layout-registry";

        ResponseEntity<String> queue = restTemplate.exchange(base + "/review-queue", HttpMethod.GET,
                new HttpEntity<>(bearerFor(admin)), String.class);
        assertThat(queue.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode entry = null;
        for (JsonNode node : mapper.readTree(queue.getBody()).get("data")) {
            if (fp.equals(node.get("fingerprint").asText())) entry = node;
        }
        assertThat(entry).isNotNull();
        assertThat(entry.get("reviewReasons").toString()).contains("NEW_LAYOUT", "BLANK_DESCRIPTIONS");

        ResponseEntity<String> renamed = restTemplate.exchange(base + "/" + fp, HttpMethod.PATCH,
                new HttpEntity<>("{\"name\":\"Test Layout\",\"status\":\"UNDER_REVIEW\"}", bearerFor(admin)), String.class);
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> resolved = restTemplate.exchange(base + "/" + fp + "/review/resolve", HttpMethod.POST,
                new HttpEntity<>(bearerFor(admin)), String.class);
        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);

        RegisteredLayout layout = layout(fp);
        assertThat(layout.getName()).isEqualTo("Test Layout");
        assertThat(layout.getStatus()).isEqualTo(RegisteredLayout.Status.UNDER_REVIEW);
        assertThat(layout.isNeedsReview()).isFalse();
        assertThat(auditLogRepository.findAll()).filteredOn(a -> layout.getId().equals(a.getEntityId()))
                .extracting(a -> a.getAction())
                .contains("LAYOUT_UPDATED", "LAYOUT_REVIEW_RESOLVED");
    }

    @Test
    void aUserWithoutTheLayoutPermissionsCannotReadOrWrite() {
        User ops = createUser("OPS");
        String base = "/api/v1/admin/imports/layout-registry";

        ResponseEntity<String> read = restTemplate.exchange(base + "/review-queue", HttpMethod.GET,
                new HttpEntity<>(bearerFor(ops)), String.class);
        ResponseEntity<String> write = restTemplate.exchange(base + "/profiles", HttpMethod.POST,
                new HttpEntity<>("{\"name\":\"Nope\"}", bearerFor(ops)), String.class);

        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(write.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------ wired into real staging

    /** A real CSV upload through ImportService reaches the registry and the review queue: the
     *  seam between staging and this feature, not just the service in isolation. The extra column
     *  has a random name so the layout is guaranteed never to have been seen. */
    @Test
    void aRealCsvStagingOfAnUnseenLayoutIsRegisteredAndFlagged() throws Exception {
        String extraColumn = "Note" + UUID.randomUUID().toString().substring(0, 6);
        String csv = "Date,Description,Amount,Type," + extraColumn + "\n"
                + "2026-07-01,SAMPLE PAYER CREDIT,10000.00,CREDIT,x\n"
                + "2026-07-29,SAMPLE SHOP DEBIT,1628.00,DEBIT,y\n";
        User user = createUser("USER");

        var staged = importService.parseAndStageWithSession(user.getId(), "sample.csv",
                csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        Map<String, Object> analysis = jdbc.queryForMap(
                "SELECT layout_fingerprint, reference FROM statement_analysis_sessions WHERE import_session_id = ?",
                staged.sessionId());
        String fp = (String) analysis.get("layout_fingerprint");
        RegisteredLayout layout = layout(fp);
        assertThat(layout.getStagingCount()).isEqualTo(1);
        assertThat(layout.getObservationCount()).isZero();
        assertThat(layout.getReviewReasons()).contains("NEW_LAYOUT");
        assertThat(layout.getReviewAnalysisReference()).isEqualTo(analysis.get("reference"));
        verify(alertService, timeout(5000).times(1)).alertLayoutNeedsReview(eq(fp), any(), eq((String) analysis.get("reference")));
    }

    /** Real staging of a file with no recognisable transaction table: the failure path records the
     *  headerless fingerprint in the analysis table (as it always has), and the layout review must
     *  neither register it nor alert on it. */
    @Test
    void aRealUploadWithNoRecognisableTableDoesNotRegisterOrAlert() {
        User user = createUser("USER");
        String headerless = new DocumentContext("CSV", "any").buildFingerprint();
        long before = layoutRepository.findByFingerprint(headerless).map(RegisteredLayout::getStagingCount).orElse(0L);
        String marker = "not-a-statement-" + UUID.randomUUID();

        assertThatThrownBy(() -> importService.parseAndStageWithSession(user.getId(), marker + ".csv",
                ("hello world\nthis is " + marker + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(RuntimeException.class);

        String recorded = jdbc.queryForObject(
                "SELECT layout_fingerprint FROM statement_analysis_sessions WHERE file_name = ?", String.class,
                marker + ".csv");
        assertThat(recorded).isEqualTo(headerless);
        assertThat(layoutRepository.findByFingerprint(headerless).map(RegisteredLayout::getStagingCount).orElse(0L))
                .isEqualTo(before);
        verify(alertService, after(500).never()).alertLayoutNeedsReview(eq(headerless), any(), any());
    }
}
