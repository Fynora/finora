package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.entity.RegisteredLayout;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.imports.analysis.DocumentIdentity;
import com.finora.imports.analysis.ParseDiagnostics;
import com.finora.imports.analysis.StatementAnalysisRecorder;
import com.finora.imports.analysis.StatementAnalysisSession;
import com.finora.repository.AccountRepository;
import com.finora.repository.RegisteredLayoutRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Automatic layout profile grouping (V244) against real PostgreSQL: at staging, the rules that keep
 * an operator's decisions final and a conflicting identity flagged rather than moved, and the
 * one-time backfill from stored evidence. The KOTAK profile is shared by every test (and by any
 * other IT that stages a Kotak statement), so versions are asserted relative to each other.
 */
class LayoutProfileAutoGroupingIT extends AbstractIntegrationTest {

    @Autowired private LayoutReviewService reviewService;
    @Autowired private LayoutCurationService curationService;
    @Autowired private LayoutProfileBackfill backfill;
    @Autowired private LayoutRegistryService registryService;
    @Autowired private RegisteredLayoutRepository layoutRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private StatementAnalysisRecorder analysisRecorder;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private com.finora.repository.AuditLogRepository auditLogRepository;
    @MockitoBean private LayoutReviewAlertService alertService;

    private static final LayoutIdentity KOTAK_CC = new LayoutIdentity("KOTAK", "Kotak Mahindra Bank", Set.of("CREDIT_CARD"));
    private static final LayoutIdentity HDFC_CC = new LayoutIdentity("HDFC", "HDFC Bank", Set.of("CREDIT_CARD"));

    private static String fingerprint() {
        return "FP-T-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static List<StagedRow> cleanRows() {
        return List.of(new StagedRow(LocalDate.of(2026, 1, 15), "SAMPLE STORE", new BigDecimal("1.00"), "EXPENSE",
                "Other", "default", null, false, null, null));
    }

    private RegisteredLayout layout(String fp) {
        return layoutRepository.findByFingerprint(fp).orElseThrow();
    }

    private void stage(String fp, LayoutIdentity identity) {
        reviewService.onStaged(fp, "PDF", cleanRows(), List.of(), "SA-AUTO", identity);
    }

    private UUID admin() {
        User user = new User();
        user.setEmail("layout-auto-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Layout Auto IT Admin");
        user.setRole("ADMIN");
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user).getId();
    }

    // ------------------------------------------------------------------ at staging

    @Test
    void aBanksNextLayoutJoinsItsProfileAsTheNextVersionOnItsOwn() {
        String older = fingerprint();
        String newer = fingerprint();

        stage(older, KOTAK_CC);
        stage(newer, KOTAK_CC);
        stage(older, KOTAK_CC); // seen again: stays where it is

        RegisteredLayout v1 = layout(older);
        RegisteredLayout v2 = layout(newer);
        assertThat(v1.getProfileId()).isNotNull().isEqualTo(v2.getProfileId());
        assertThat(v1.getProfileLinkSource()).isEqualTo("AUTO");
        assertThat(v2.getProfileVersion()).isEqualTo(v1.getProfileVersion() + 1);
        String name = jdbc.queryForObject("SELECT name FROM layout_profile WHERE id = ?", String.class, v1.getProfileId());
        assertThat(name).isEqualTo("Kotak Mahindra Bank — Credit Card");
    }

    /** An older format grouped late -- its first statements carried no bank evidence -- still reads
     *  as the earlier version: it goes before the newer layout, which moves up one. */
    @Test
    void anOlderLayoutGroupedLateStillComesFirst() {
        String bankId = "TEST" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        LayoutIdentity identity = new LayoutIdentity(bankId, "Test Bank " + bankId, Set.of("CREDIT_CARD"));
        Instant now = Instant.now();
        String older = fingerprint();
        String newer = fingerprint();
        registeredEarlier(older, now.minus(90, ChronoUnit.DAYS));
        registeredEarlier(newer, now.minus(5, ChronoUnit.DAYS));

        stage(newer, identity);
        assertThat(layout(newer).getProfileVersion()).isEqualTo(1);

        stage(older, identity);
        assertThat(layout(older).getProfileVersion()).isEqualTo(1);
        assertThat(layout(newer).getProfileVersion()).isEqualTo(2);
        assertThat(layout(older).getProfileId()).isEqualTo(layout(newer).getProfileId());
    }

    @Test
    void anOperatorsLinkIsPlacedByFirstAppearanceToo() {
        UUID admin = admin();
        var profile = curationService.createProfile(admin, "Test Chronological " + UUID.randomUUID());
        Instant now = Instant.now();
        String a = fingerprint();
        String b = fingerprint();
        String c = fingerprint();
        registeredEarlier(a, now.minus(30, ChronoUnit.DAYS));
        registeredEarlier(b, now.minus(20, ChronoUnit.DAYS));
        registeredEarlier(c, now.minus(10, ChronoUnit.DAYS));

        curationService.linkToProfile(admin, a, profile.id());
        curationService.linkToProfile(admin, c, profile.id());
        var middle = curationService.linkToProfile(admin, b, profile.id());

        assertThat(middle.profileVersion()).isEqualTo(2);
        assertThat(middle.profileLinkSource()).isEqualTo("MANUAL");
        assertThat(layout(a).getProfileVersion()).isEqualTo(1);
        assertThat(layout(c).getProfileVersion()).isEqualTo(3);
    }

    @Test
    void aVersionMoveIsRecordedOnTheRowAndAudited() {
        String bankId = "TEST" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        LayoutIdentity identity = new LayoutIdentity(bankId, "Test Bank " + bankId, Set.of("CREDIT_CARD"));
        Instant now = Instant.now();
        String older = fingerprint();
        String newer = fingerprint();
        registeredEarlier(older, now.minus(90, ChronoUnit.DAYS));
        registeredEarlier(newer, now.minus(5, ChronoUnit.DAYS));
        stage(newer, identity);

        stage(older, identity);

        RegisteredLayout moved = layout(newer);
        assertThat(moved.getProfileVersion()).isEqualTo(2);
        assertThat(moved.getPreviousProfileVersion()).isEqualTo(1);
        assertThat(moved.getProfileVersionChangedAt()).isNotNull();
        assertThat(layout(older).getPreviousProfileVersion()).isNull();
        assertThat(auditLogRepository.findAll()).filteredOn(a -> moved.getProfileId().equals(a.getEntityId()))
                .extracting(a -> a.getAction()).contains("LAYOUT_PROFILE_VERSIONS_SHIFTED");
    }

    @Test
    void returningARemovedLayoutToAutomaticRegroupsItFromStoredEvidence() {
        UUID admin = admin();
        User user = new User();
        user.setEmail("layout-return-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Return User");
        UUID userId = userRepository.save(user).getId();
        String fp = fingerprint();
        registeredEarlier(fp, Instant.now().minus(3, ChronoUnit.DAYS));
        confirmedImport(userId, account(userId, "KOTAK", Account.Type.CREDIT_CARD).getId(), fp);
        backfill.run();
        UUID kotakProfile = layout(fp).getProfileId();
        assertThat(kotakProfile).isNotNull();
        curationService.unlinkFromProfile(admin, fp);
        assertThat(layout(fp).getProfileLinkSource()).isEqualTo("MANUAL");

        var entry = curationService.returnToAutomatic(admin, fp);

        assertThat(entry.profileId()).isEqualTo(kotakProfile);
        assertThat(entry.profileLinkSource()).isEqualTo("AUTO");
        assertThat(layout(fp).getProfileLinkSource()).isEqualTo("AUTO");
    }

    @Test
    void returningALayoutWithNoEvidenceLeavesItUndecidedForTheNextUpload_andAnAutomaticOneCannotBeReturned() {
        UUID admin = admin();
        String fp = fingerprint();
        stage(fp, null);
        var own = curationService.createProfile(admin, "Test Return Profile " + UUID.randomUUID());
        curationService.linkToProfile(admin, fp, own.id());

        var entry = curationService.returnToAutomatic(admin, fp);
        assertThat(entry.profileId()).isNull();
        assertThat(entry.profileLinkSource()).isNull();

        stage(fp, KOTAK_CC); // the next upload groups it
        assertThat(layout(fp).getProfileLinkSource()).isEqualTo("AUTO");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> curationService.returnToAutomatic(admin, fp))
                .hasMessageContaining("already grouped automatically");
    }

    @Test
    void theAlertNamesTheProfileAndVersionTheLayoutJoined() {
        String bankId = "TEST" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        LayoutIdentity identity = new LayoutIdentity(bankId, "Test Bank " + bankId, Set.of("SAVINGS"));
        String fp = fingerprint();

        stage(fp, identity);

        verify(alertService, timeout(5000)).alertLayoutNeedsReview(eq(fp), any(), any(),
                eq("Test Bank " + bankId + " — Savings / Current v1"));
    }

    @Test
    void noIdentityLeavesTheLayoutUngrouped() {
        String fp = fingerprint();

        stage(fp, null);

        assertThat(layout(fp).getProfileId()).isNull();
        assertThat(layout(fp).getProfileLinkSource()).isNull();
    }

    @Test
    void aLayoutLaterSeenAsAnotherBankIsFlaggedNotMoved() {
        String fp = fingerprint();
        stage(fp, KOTAK_CC);
        UUID kotakProfile = layout(fp).getProfileId();

        stage(fp, HDFC_CC);

        RegisteredLayout after = layout(fp);
        assertThat(after.getProfileId()).isEqualTo(kotakProfile);
        assertThat(after.getReviewReasons()).contains("IDENTITY_CONFLICT");
    }

    @Test
    void anOperatorsRemovalIsFinal_andAnOperatorsLinkIsNeverMoved() {
        UUID admin = admin();
        String removed = fingerprint();
        stage(removed, KOTAK_CC);
        curationService.unlinkFromProfile(admin, removed);

        stage(removed, KOTAK_CC);
        assertThat(layout(removed).getProfileId()).isNull();
        assertThat(layout(removed).getProfileLinkSource()).isEqualTo("MANUAL");

        String placed = fingerprint();
        registryService.observe(placed, "PDF", null);
        var ownProfile = curationService.createProfile(admin, "Test Own Profile " + UUID.randomUUID());
        curationService.linkToProfile(admin, placed, ownProfile.id());

        stage(placed, KOTAK_CC);
        assertThat(layout(placed).getProfileId()).isEqualTo(ownProfile.id());
        assertThat(layout(placed).getReviewReasons()).doesNotContain("IDENTITY_CONFLICT");
    }

    @Test
    void anOperatorsProfileWithTheSameNameIsAdoptedRatherThanDuplicated() {
        UUID admin = admin();
        String bankId = "TEST" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        LayoutIdentity identity = new LayoutIdentity(bankId, "Test Bank " + bankId, Set.of("SAVINGS"));
        var handMade = curationService.createProfile(admin, identity.profileName());
        String fp = fingerprint();

        stage(fp, identity);

        assertThat(layout(fp).getProfileId()).isEqualTo(handMade.id());
        assertThat(jdbc.queryForObject("SELECT auto_key FROM layout_profile WHERE id = ?", String.class, handMade.id()))
                .isEqualTo(identity.key());
    }

    // ------------------------------------------------------------------ backfill

    private Account account(UUID userId, String bankId, Account.Type type) {
        Account account = new Account();
        account.setUserId(userId);
        account.setName("Backfill test account");
        account.setAccountType(type);
        account.setBankId(bankId);
        account.setBalance(BigDecimal.ZERO);
        return accountRepository.save(account);
    }

    private void confirmedImport(UUID userId, UUID accountId, String fp) {
        StatementImport si = new StatementImport();
        si.setUserId(userId);
        si.setAccountId(accountId);
        si.setFileName("backfill.pdf");
        si.setFileContent(new byte[]{1});
        si.setContentHash("backfill-" + UUID.randomUUID());
        si.setLayoutFingerprint(fp);
        statementImportRepository.save(si);
    }

    /** Registers a layout the way a pre-V244 confirmed import did: undecided, with a chosen first-seen. */
    private void registeredEarlier(String fp, Instant firstSeen) {
        layoutRepository.observe(fp, "PDF", null, firstSeen);
    }

    @Test
    void theBackfillGroupsExistingLayoutsFromConfirmedImportsAndAnalysisEvidence_inFirstSeenOrder() {
        User user = new User();
        user.setEmail("layout-backfill-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Backfill User");
        UUID userId = userRepository.save(user).getId();
        Instant now = Instant.now();

        // The older Kotak layout: evidence only from a confirmed import into a Kotak credit card.
        String oldKotak = fingerprint();
        registeredEarlier(oldKotak, now.minus(60, ChronoUnit.DAYS));
        confirmedImport(userId, account(userId, "KOTAK", Account.Type.CREDIT_CARD).getId(), oldKotak);

        // The newer one: evidence only from a staged statement's detected identity.
        String newKotak = fingerprint();
        registeredEarlier(newKotak, now.minus(1, ChronoUnit.DAYS));
        analysisRecorder.recordParsed(userId, StatementAnalysisSession.Source.CUSTOMER_IMPORT, "x.pdf", "PDF", 1L,
                newKotak, 1, 1L, ParseDiagnostics.of(1, Map.of())
                        .withIdentity(new DocumentIdentity(true, "Kotak Mahindra Bank", "CREDIT_CARD")));

        // Evidence that disagrees: one import into a Kotak card, another into an HDFC savings account.
        String conflicted = fingerprint();
        registeredEarlier(conflicted, now.minus(30, ChronoUnit.DAYS));
        confirmedImport(userId, account(userId, "KOTAK", Account.Type.CREDIT_CARD).getId(), conflicted);
        confirmedImport(userId, account(userId, "HDFC", Account.Type.SAVINGS).getId(), conflicted);

        // No evidence at all.
        String unknown = fingerprint();
        registeredEarlier(unknown, now.minus(10, ChronoUnit.DAYS));

        backfill.run();

        RegisteredLayout v1 = layout(oldKotak);
        RegisteredLayout v2 = layout(newKotak);
        assertThat(v1.getProfileLinkSource()).isEqualTo("AUTO");
        assertThat(v1.getProfileId()).isNotNull().isEqualTo(v2.getProfileId());
        assertThat(v2.getProfileVersion()).isGreaterThan(v1.getProfileVersion());
        assertThat(layout(conflicted).getProfileId()).isNull();
        assertThat(layout(conflicted).getReviewReasons()).containsExactly("IDENTITY_CONFLICT");
        assertThat(layout(unknown).getProfileId()).isNull();
        assertThat(layout(unknown).isNeedsReview()).isFalse();

        // Idempotent: a second sweep changes nothing about these.
        int versionBefore = layout(newKotak).getProfileVersion();
        backfill.run();
        assertThat(layout(newKotak).getProfileVersion()).isEqualTo(versionBefore);
        assertThat(layout(oldKotak).getProfileVersion()).isEqualTo(v1.getProfileVersion());
    }

    @Test
    void theBackfillNeverGroupsTheHeaderlessFingerprint() {
        String headerless = new DocumentContext("PDF", "any").buildFingerprint();
        // A row a pre-V244 confirmed import may have left (registration now skips it).
        layoutRepository.observe(headerless, "PDF", null, Instant.now());
        jdbc.update("UPDATE layout_registry SET profile_id = NULL, profile_version = NULL, profile_link_source = NULL "
                + "WHERE fingerprint = ?", headerless);
        User user = new User();
        user.setEmail("layout-backfill-hl-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Backfill User");
        UUID userId = userRepository.save(user).getId();
        confirmedImport(userId, account(userId, "AU", Account.Type.CREDIT_CARD).getId(), headerless);

        backfill.run();

        assertThat(layout(headerless).getProfileId()).isNull();
    }
}
