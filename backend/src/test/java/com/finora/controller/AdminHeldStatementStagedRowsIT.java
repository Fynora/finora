package com.finora.controller;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.Permission;
import com.finora.entity.Role;
import com.finora.entity.User;
import com.finora.imports.storage.ContentAddress;
import com.finora.imports.storage.StatementStorage;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.RoleRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /{heldId}/staged-rows} -- the rows a hold staged, which are the statement's content as
 * much as the file is, so they carry the same role pin as the original view. Split out of the
 * removed document-download IT, whose download cases now live in {@code AdminOriginalViewIT} and
 * {@code OriginalContentAccessIT}.
 *
 * <p>{@code ImportJob} never carries database-held bytes -- {@code getFileContent()} always
 * returns null by design (BH-045: "a job carries an address, never the bytes") -- so the held job is
 * stored through a real, configured {@link StatementStorage}.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-held-statement-staged-rows-it"
})
class AdminHeldStatementStagedRowsIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private HeldStatementRepository heldStatementRepository;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private EntityManager entityManager;
    @Autowired private StatementStorage storage;

    private static final byte[] PDF_BYTES = "%PDF-1.4 fixture bytes".getBytes(StandardCharsets.UTF_8);

    private User createUser(String role) {
        User user = new User();
        user.setEmail("held-dl-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Held Download IT User");
        user.setRole(role);
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    /**
     * A support-portal account: holds {@code TRUST_REVIEW_MANAGE} through a real, attached RBAC
     * role -- so it can reach the queue and open a hold's evidence -- but its legacy {@code role}
     * column is deliberately NOT "ADMIN" or "SUPER_ADMIN", so it carries neither {@code ROLE_ADMIN}
     * nor {@code ROLE_SUPER_ADMIN}. This is exactly the future support role the repository owner's
     * decision names: permitted to work the queue, never permitted to take the document.
     */
    private User createSupportUserWithTrustReviewPermissionButNoAdminRole() {
        Permission trustReviewManage = entityManager
                .createQuery("SELECT p FROM Permission p WHERE p.name = :name", Permission.class)
                .setParameter("name", "TRUST_REVIEW_MANAGE")
                .getSingleResult();
        Role supportRole = new Role();
        supportRole.setName("TRUST_REVIEW_SUPPORT_" + UUID.randomUUID().toString().substring(0, 8));
        supportRole.setDescription("AdminHeldStatementStagedRowsIT fixture role");
        supportRole.getPermissions().add(trustReviewManage);
        roleRepository.save(supportRole);

        User user = createUser("SUPPORT");
        user.getRoles().add(supportRole);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** A job in exactly the state the worker's trust gate leaves a held import in, with real PDF
     *  bytes written to the (filesystem-backed) object store. */
    private HeldStatement seedHold(String heldId) {
        User owner = createUser("USER");
        ContentAddress address = storage.store(PDF_BYTES);
        ImportJob job = new ImportJob(owner.getId(), "hdfc-june.pdf",
                address.hash(), address.key(), "PDF");
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

    // ---------------------------------------------------------------- the staged rows, same gate

    private ResponseEntity<String> stagedRows(String heldId, User caller) {
        return restTemplate.exchange("/api/v1/admin/held-statements/" + heldId + "/staged-rows",
                HttpMethod.GET, new HttpEntity<>(bearerFor(caller)), String.class);
    }

    /** The rows are the statement's content as much as the file is, so the queue permission alone
     *  does not reach them either. */
    @Test
    void stagedRowsRefuseAnyRoleBelowAdmin() {
        seedHold("HLD-2026-200101");

        ResponseEntity<String> response = stagedRows("HLD-2026-200101", createSupportUserWithTrustReviewPermissionButNoAdminRole());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** The same AND as the original view's: the admin role without the permission is refused too. */
    @Test
    void stagedRowsRefuseAnAdminRoleAccountWithoutTheTrustReviewPermission() {
        seedHold("HLD-2026-200102");
        User user = new User();
        user.setEmail("held-dl-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Held Download IT User");
        user.setRole("ADMIN");
        user.setPhoneVerified(true);
        userRepository.save(user);

        ResponseEntity<String> response = stagedRows("HLD-2026-200102", user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** Audited before the rows are read, as the original view is: this hold's session never existed, so
     *  the read is refused, and the attempt is still on record. */
    @Test
    void everyStagedRowsReadIsAuditedEvenWhenTheRowsAreGone() {
        HeldStatement held = seedHold("HLD-2026-200103");
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = stagedRows("HLD-2026-200103", admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("are gone");
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(held.getId())).anySatisfy(entry -> {
            assertThat(entry.getAction()).isEqualTo("TRUST_REVIEW_STAGED_ROWS_VIEWED");
            assertThat(entry.getUserId()).isEqualTo(admin.getId());
        });
    }
}
