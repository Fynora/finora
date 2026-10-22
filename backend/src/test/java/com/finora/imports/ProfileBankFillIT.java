package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.entity.RegisteredLayout;
import com.finora.entity.User;
import com.finora.repository.RegisteredLayoutRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The profile's bank reaching a real staging run (spec 2026-10-22, section 2): through
 * ImportService, into the stored session and the response, without touching the layout's own
 * evidence or grouping.
 *
 * <p>Each test gets its own layout: HDFC's statement columns plus one randomly named column, so no
 * test sees another's profile link in the shared database. Every value is synthetic.
 */
class ProfileBankFillIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private LayoutCurationService curationService;
    @Autowired private RegisteredLayoutRepository layoutRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private LayoutReviewAlertService alertService;

    /** HDFC's savings columns, nothing above the header, one debit and one credit. */
    private static byte[] hdfcStyleCsv(String extraColumn) {
        String csv = "Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance," + extraColumn + "\n"
                + "01/04/25,UPI-SYNTHETIC SHOP-SHOP@OKAXIS-UTIB0XXXXXX-100000000001-UPI,0000100000000001,01/04/25,100.00,,1900.00,x\n"
                + "03/04/25,SYNTHETIC SALARY CREDIT,0000100000000002,03/04/25,,5000.00,6900.00,y\n";
        return csv.getBytes(StandardCharsets.UTF_8);
    }

    private static String extraColumn() {
        return "Remark" + UUID.randomUUID().toString().substring(0, 6);
    }

    private User user(String role) {
        User user = new User();
        user.setEmail("profile-bank-fill-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Profile Bank Fill IT");
        user.setRole(role);
        user.setAccountScope("ADMIN".equals(role) ? User.SCOPE_ADMIN : user.getAccountScope());
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    /** Stages once to register the layout, then links it to a new profile with {@code bankId}. */
    private String layoutInProfile(byte[] csv, String bankId) throws Exception {
        User first = user("USER");
        var staged = importService.parseAndStageWithSession(first.getId(), "statement.csv", csv);
        String fp = fingerprintOf(staged.sessionId());
        UUID admin = user("ADMIN").getId();
        var profile = curationService.createProfile(admin, "Test Fill Profile " + UUID.randomUUID(), bankId);
        curationService.linkToProfile(admin, fp, profile.id());
        return fp;
    }

    private String fingerprintOf(UUID sessionId) {
        return jdbc.queryForObject("SELECT layout_fingerprint FROM import_sessions WHERE id = ?", String.class, sessionId);
    }

    private DetectedAccountInfo storedDetected(UUID userId, UUID sessionId) {
        return importSessionService.readDetectedAccount(importSessionService.getOwnedSession(userId, sessionId));
    }

    private RegisteredLayout layout(String fp) {
        return layoutRepository.findByFingerprint(fp).orElseThrow();
    }

    @Test
    void unknownBankIsFilledFromTheLayoutsProfile() throws Exception {
        byte[] csv = hdfcStyleCsv(extraColumn());
        String fp = layoutInProfile(csv, "HDFC");
        User user = user("USER");

        var staged = importService.parseAndStageWithSession(user.getId(), "statement.csv", csv);

        DetectedAccountInfo shown = staged.staging().detectedAccount();
        assertThat(shown.bank().id()).isEqualTo("HDFC");
        assertThat(shown.bankSource()).isEqualTo("PROFILE");
        assertThat(shown.suggestedName()).isEqualTo("HDFC Bank");
        DetectedAccountInfo stored = storedDetected(user.getId(), staged.sessionId());
        assertThat(stored.bank().id()).isEqualTo("HDFC");
        assertThat(stored.bankSource()).isEqualTo("PROFILE");
        // The evidence row records what the FILE said, which is nothing.
        Map<String, Object> analysis = jdbc.queryForMap(
                "SELECT bank_name, layout_fingerprint FROM statement_analysis_sessions WHERE import_session_id = ?",
                staged.sessionId());
        assertThat(analysis.get("layout_fingerprint")).isEqualTo(fp);
        assertThat(analysis.get("bank_name")).isNull();
    }

    @Test
    void noProfileMeansNone() throws Exception {
        User user = user("USER");

        var staged = importService.parseAndStageWithSession(user.getId(), "statement.csv", hdfcStyleCsv(extraColumn()));

        assertThat(staged.staging().detectedAccount().bank().id()).isEqualTo("OTHER");
        assertThat(staged.staging().detectedAccount().bankSource()).isEqualTo("NONE");
    }

    @Test
    void fileNamedBankWinsAndRaisesAConflict() throws Exception {
        byte[] csv = hdfcStyleCsv(extraColumn());
        String fp = layoutInProfile(csv, "ICICI");
        curationService.resolveReview(user("ADMIN").getId(), fp);
        User user = user("USER");

        var staged = importService.parseAndStageWithSession(user.getId(), "HDFC_statement.csv", csv);

        assertThat(staged.staging().detectedAccount().bank().id()).isEqualTo("HDFC");
        assertThat(staged.staging().detectedAccount().bankSource()).isEqualTo("FILE");
        assertThat(layout(fp).getReviewReasons()).contains("IDENTITY_CONFLICT");
    }

    @Test
    void aProfileThatAgreesWithTheFileRaisesNoConflict() throws Exception {
        byte[] csv = hdfcStyleCsv(extraColumn());
        String fp = layoutInProfile(csv, "HDFC");
        curationService.resolveReview(user("ADMIN").getId(), fp);
        User user = user("USER");

        var staged = importService.parseAndStageWithSession(user.getId(), "HDFC_statement.csv", csv);

        assertThat(staged.staging().detectedAccount().bankSource()).isEqualTo("FILE");
        assertThat(layout(fp).getReviewReasons()).doesNotContain("IDENTITY_CONFLICT");
    }

    @Test
    void filledBankDoesNotRegroupTheLayout() throws Exception {
        byte[] csv = hdfcStyleCsv(extraColumn());
        String fp = layoutInProfile(csv, "HDFC");
        RegisteredLayout before = layout(fp);

        importService.parseAndStageWithSession(user("USER").getId(), "statement.csv", csv);

        RegisteredLayout after = layout(fp);
        assertThat(after.getProfileId()).isEqualTo(before.getProfileId());
        assertThat(after.getProfileLinkSource()).isEqualTo("MANUAL");
        assertThat(after.getProfileVersion()).isEqualTo(before.getProfileVersion());
    }

    @Test
    void heldStatementReparseIsFilledToo() throws Exception {
        byte[] csv = hdfcStyleCsv(extraColumn());
        layoutInProfile(csv, "HDFC");
        User user = user("USER");

        var parsed = importService.reparse(user.getId(), "statement.csv", csv, "CSV", null);
        var staged = importService.stageReparsed(user.getId(), "statement.csv", csv, parsed);

        DetectedAccountInfo stored = storedDetected(user.getId(), staged.sessionId());
        assertThat(stored.bank().id()).isEqualTo("HDFC");
        assertThat(stored.bankSource()).isEqualTo("PROFILE");
    }

    @Test
    void theDebitAndTheCreditStillStageTheirOwnWay() throws Exception {
        byte[] csv = hdfcStyleCsv(extraColumn());
        layoutInProfile(csv, "HDFC");

        var staged = importService.parseAndStageWithSession(user("USER").getId(), "statement.csv", csv);

        assertThat(staged.staging().rows()).extracting(r -> r.type()).containsExactly("EXPENSE", "INCOME");
        assertThat(staged.staging().rows()).extracting(r -> r.amount().toPlainString()).containsExactly("100.00", "5000.00");
        assertThat(List.of(staged.staging().rows().get(0).date().toString(), staged.staging().rows().get(1).date().toString()))
                .containsExactly("2025-04-01", "2025-04-03");
    }
}
