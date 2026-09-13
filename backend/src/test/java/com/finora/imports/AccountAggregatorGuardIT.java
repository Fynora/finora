package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.AuditLog;
import com.finora.entity.User;
import com.finora.integrations.setu.AccountAggregatorLink;
import com.finora.integrations.setu.AccountAggregatorLinkRepository;
import com.finora.integrations.setu.AccountAggregatorLinkStatus;
import com.finora.integrations.setu.FiType;
import com.finora.repository.AccountRepository;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AccountAggregatorGuardTest} proves the hatch's decision logic against mocks, which is
 * exactly why it could not have caught this: only a real transaction manager shows whether the
 * hatch-used audit row actually survives the rest of the request.
 *
 * <p>Regression test for a real bug found while reviewing this plan, not written speculatively.
 * {@code ImportService.confirm(...)} -- the guard's only real caller -- is plain
 * {@code @Transactional} (no {@code noRollbackFor}), and there is substantial processing after
 * the guard's own check succeeds (parsing, persisting transactions, reconciliation). The guard's
 * hatch-open branch does not itself throw, but a plain {@code auditService.record(...)} call
 * still joins that same transaction -- so if anything later in the same {@code confirm(...)} call
 * throws for any unrelated reason, Spring's default rollback-on-RuntimeException rule discards the
 * "hatch was used" audit row along with everything else, even though the hatch genuinely opened.
 * This is the identical mechanism {@code UserAccountLifecycleServiceIT} and
 * {@code PasswordChangeServiceIT} already document for other call sites in this codebase -- see
 * {@code AuditService.recordEvenOnRollback}'s own doc comment. Reproduced here directly against
 * the guard (not the full {@code confirm()} pipeline) via a {@link TransactionTemplate}
 * standing in for "some unrelated failure elsewhere in the same request."
 */
class AccountAggregatorGuardIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountAggregatorLinkRepository aaLinks;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private AccountAggregatorGuard guard;
    @Autowired private PlatformTransactionManager transactionManager;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("aa-guard-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("AA Guard Test User");
        userId = userRepository.save(user).getId();

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Stale AA Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        account.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        accountId = accountRepository.save(account).getId();

        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(userId);
        link.setAccountId(accountId);
        link.setFiType(FiType.DEPOSIT);
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setLinkIdempotencyKey("aa-guard-it-" + UUID.randomUUID());
        link.setLastSyncedAt(Instant.now().minus(Duration.ofHours(96))); // well past the 72h threshold
        aaLinks.save(link);
    }

    @Test
    void hatchUsedAuditRow_survivesARollbackCausedByUnrelatedCodeLaterInTheSameTransaction() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(status -> {
            guard.checkNotActivelySynced(userId, accountId); // stale link -- hatch opens, audit written
            // Stands in for "confirm() fails later for an unrelated reason" -- a parsing error, a
            // constraint violation, anything downstream of the guard's own check succeeding.
            throw new RuntimeException("unrelated failure elsewhere in confirm()");
        }));

        List<AuditLog> logs = auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId);
        assertThat(logs).anySatisfy(log ->
                assertThat(log.getAction()).isEqualTo("ACCOUNT_AGGREGATOR_OUTAGE_ESCAPE_HATCH_USED"));
    }
}
