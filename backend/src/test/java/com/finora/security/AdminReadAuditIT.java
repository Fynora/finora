package com.finora.security;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.AuditLog;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Privacy workstream 9: admin reads of customer data are recorded under every admin path, not only
 * {@code /api/v1/admin/users/{userId}/...}. Over real HTTP with a real admin session, read back from
 * the audit table. Every value is synthetic.
 */
class AdminReadAuditIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private AdminReadOwnerResolver ownerResolver;

    @Test
    void readingOneAccountsCoverage_isRecordedAgainstTheAccountsOwner() {
        User admin = user("SUPER_ADMIN");
        User customer = user("USER");
        Account account = account(customer);

        ResponseEntity<String> response = get(admin, "/api/v1/admin/accounts/" + account.getId() + "/coverage");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<AuditLog> rows = viewedRowsFor(customer.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getUserId()).as("attributed to the admin").isEqualTo(admin.getId());
        assertThat(rows.get(0).getMetadata())
                .containsEntry("endpoint", "/api/v1/admin/accounts/{accountId}/coverage")
                .containsEntry("accountId", account.getId().toString());
    }

    @Test
    void aListingOfCustomersImports_isRecordedOnceWithItsFilters() {
        User admin = user("SUPER_ADMIN");

        ResponseEntity<String> response = get(admin, "/api/v1/admin/system/recent-imports?limit=5");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<AuditLog> rows = rowsByAdmin(admin.getId(), AdminUserDataReadAuditInterceptor.LISTED_ACTION);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getEntityId()).isNull();
        assertThat(rows.get(0).getMetadata()).containsEntry("endpoint", "/api/v1/admin/system/recent-imports");
        assertThat(rows.get(0).getMetadata().get("query")).isEqualTo(java.util.Map.of("limit", "5"));
    }

    @Test
    void platformCounts_areNotRecorded() {
        User admin = user("SUPER_ADMIN");

        ResponseEntity<String> response = get(admin, "/api/v1/admin/stats/overview");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rowsByAdmin(admin.getId(), AdminUserDataReadAuditInterceptor.LISTED_ACTION)).isEmpty();
        assertThat(rowsByAdmin(admin.getId(), AdminUserDataReadAuditInterceptor.ACTION)).isEmpty();
    }

    @Test
    void aRefusedRead_leavesNoRow() {
        User plain = user("USER");
        Account account = account(user("USER"));

        ResponseEntity<String> response = get(plain, "/api/v1/admin/accounts/" + account.getId() + "/coverage");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rowsByAdmin(plain.getId(), AdminUserDataReadAuditInterceptor.ACTION)).isEmpty();
    }

    /** Every lookup runs against the real schema: an id that names nothing gives no owner, not an
     *  error, so a wrong table or column name fails here rather than in production. */
    @Test
    void everyOwnerLookupRunsAgainstTheRealSchema() {
        for (AdminReadAuditPolicy.Owner owner : AdminReadAuditPolicy.Owner.values()) {
            String value = owner == AdminReadAuditPolicy.Owner.USER_EMAIL_PARAM
                    ? "nobody-" + UUID.randomUUID() + "@example.com"
                    : owner == AdminReadAuditPolicy.Owner.HELD_STATEMENT || owner == AdminReadAuditPolicy.Owner.ANALYSIS_REFERENCE
                    ? "NOSUCHREF" : UUID.randomUUID().toString();
            UUID resolved = ownerResolver.ownerOf(owner, value);
            if (owner == AdminReadAuditPolicy.Owner.USER) {
                assertThat(resolved).as(owner.name()).isEqualTo(UUID.fromString(value));
            } else {
                assertThat(resolved).as(owner.name()).isNull();
            }
        }
    }

    @Test
    void ownerLookups_findTheCustomer() {
        User customer = user("USER");
        Account account = account(customer);

        assertThat(ownerResolver.ownerOf(AdminReadAuditPolicy.Owner.ACCOUNT, account.getId().toString()))
                .isEqualTo(customer.getId());
        assertThat(ownerResolver.ownerOf(AdminReadAuditPolicy.Owner.USER_EMAIL_PARAM, customer.getEmail()))
                .isEqualTo(customer.getId());
        assertThat(ownerResolver.ownerOf(AdminReadAuditPolicy.Owner.ACCOUNT, "not-a-uuid")).isNull();
    }

    // ---------------------------------------------------------------------------------------

    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;

    private User user(String role) {
        User user = new User();
        user.setEmail("admin-read-audit-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin Read Audit IT User");
        user.setRole(role);
        user.setAccountScope("USER".equals(role) ? User.SCOPE_USER : User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private Account account(User owner) {
        Account account = new Account();
        account.setUserId(owner.getId());
        account.setName("Synthetic Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        return accountRepository.save(account);
    }

    private ResponseEntity<String> get(User as, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, as));
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private List<AuditLog> viewedRowsFor(UUID customerId) {
        return auditLogRepository.findByEntityIdOrderByCreatedAtAsc(customerId).stream()
                .filter(row -> AdminUserDataReadAuditInterceptor.ACTION.equals(row.getAction()))
                .toList();
    }

    private List<AuditLog> rowsByAdmin(UUID adminId, String action) {
        return auditLogRepository.findByUserIdOrderByCreatedAtDesc(adminId).stream()
                .filter(row -> action.equals(row.getAction()))
                .toList();
    }
}
