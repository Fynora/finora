package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmResponse;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.NewAccountRequest;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingSessionResponse;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-30 of the 2026-09-25 corpus audit, end to end: confirming the same statement file a second time
 * without deciding anything must leave every re-imported row marked as a duplicate -- the EMI row
 * included, which the recurring-mandate exemption used to leave unmarked so its amount was added to
 * the account balance on every re-import -- and must leave {@code Account.balance} where the first
 * import put it. A third confirm where the user answers "Import anyway" on every row is the
 * opposite: nothing is marked, and the balance moves again, because the user said so.
 *
 * <p>The product contract this pins is the one the e2e suite states (smoke test 4, dashboard
 * consistency Phase 8): a repeat upload is never refused, the engine marks it, and only a human
 * decision overrides the mark.
 */
class StatementReimportMarksDuplicatesIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;

    /** No running-balance column on purpose: with one, the balance discriminator pairs the rows
     *  before the mandate exemption is ever consulted, and the EMI row's old escape route is the
     *  path under test. */
    private static final String CSV = """
            Date,Description,Amount,Type
            2026-07-01,UPI/CR/C000000000001/TEST PAYER/ptye/0000000000@ptyes/NA,10000.00,CREDIT
            2026-07-05,ECS EMI AUTO DEBIT LOAN 0000000001,4999.00,DEBIT
            2026-07-29,UPI/DR/D000000000003/TEST SHOP/apl/testshop@apl/UPI,16281.00,DEBIT
            """;

    /** Credits minus debits, the SAVINGS convention {@code AccountBalanceConvention.netDelta} applies. */
    private static final BigDecimal NET = new BigDecimal("-11280.00");

    private User user() {
        User user = new User();
        user.setEmail("reimport-marks-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Reimport Marks IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private ConfirmRequest confirmAll(StagingSessionResponse staged, UUID existingAccountId, boolean importAnyway) {
        DetectedAccountInfo d = staged.staging().detectedAccount();
        List<ConfirmedRow> rows = staged.staging().rows().stream()
                .map((StagedRow r) -> new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(),
                        r.suggestedCategory() == null ? "Other" : r.suggestedCategory(), true,
                        r.categorySource(), r.ruleId(), r.likelyDuplicate(), r.referenceNumber(),
                        r.balanceAfter(), importAnyway, r.categoryConfidence(), r.rowPosition(),
                        r.international(), r.foreignCurrency(), r.foreignAmount()))
                .toList();
        NewAccountRequest account = existingAccountId != null ? null
                : new NewAccountRequest("Reimport IT account", "SAVINGS", null, null, null,
                        null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null);
        return new ConfirmRequest(staged.sessionId(), rows, existingAccountId, account, null, null, null,
                d == null ? null : d.statementPeriodStart(), d == null ? null : d.statementPeriodEnd(),
                null, null, null, null);
    }

    private BigDecimal balanceOf(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getBalance();
    }

    @Test
    void confirmingTheSameFileAgain_marksEveryRowIncludingTheEmi_andLeavesTheBalanceAlone() throws Exception {
        User user = user();
        byte[] bytes = CSV.getBytes(StandardCharsets.UTF_8);

        // Pass 1: a fresh account, three real rows.
        StagingSessionResponse first = importService.parseAndStageWithSession(user.getId(), "july.csv", bytes);
        assertThat(first.staging().rows()).noneMatch(StagedRow::likelyDuplicate);
        importService.confirmSession(user.getId(), confirmAll(first, null, false));
        Account account = accountRepository.findByUserId(user.getId()).stream().findFirst().orElseThrow();
        List<Transaction> afterFirst = transactionRepository.findByUserId(user.getId());
        assertThat(afterFirst).hasSize(3).noneMatch(t -> t.getIsDuplicateOf() != null);
        Set<UUID> originals = afterFirst.stream().map(Transaction::getId).collect(Collectors.toSet());
        BigDecimal balanceAfterFirst = balanceOf(account);

        // Pass 2: the same bytes, no decision. Staging flags every row against the ledger; the
        // confirm lands them; reconciliation marks all three, the EMI row included, and BH-003
        // takes their contribution back off the balance.
        StagingSessionResponse second = importService.parseAndStageWithSession(user.getId(), "july.csv", bytes);
        assertThat(second.sessionId()).isNotEqualTo(first.sessionId());
        assertThat(second.staging().rows()).allMatch(StagedRow::likelyDuplicate);
        assertThat(second.staging().rows()).allMatch(r -> "EXACT".equals(r.duplicateMatch().confidence()));
        ConfirmResponse secondResponse = importService.confirmSession(user.getId(), confirmAll(second, account.getId(), false));
        assertThat(secondResponse.imported()).isEqualTo(3);
        assertThat(secondResponse.duplicatesDetected()).isEqualTo(3);

        List<Transaction> afterSecond = transactionRepository.findByUserId(user.getId());
        assertThat(afterSecond).hasSize(6);
        List<Transaction> marked = afterSecond.stream().filter(t -> t.getIsDuplicateOf() != null).toList();
        assertThat(marked).hasSize(3);
        assertThat(marked).allMatch(t -> !originals.contains(t.getId()), "only the re-imported rows are marked");
        assertThat(marked).allMatch(t -> originals.contains(t.getIsDuplicateOf()), "each points at a first-pass row");
        assertThat(marked).anyMatch(t -> t.getDescription().contains("EMI"), "the mandate row is marked too");
        assertThat(balanceOf(account)).isEqualByComparingTo(balanceAfterFirst);

        // Pass 3: the same bytes again, and the user answers "Import anyway" on every row. The
        // decision is recorded on each row, nothing new is marked, and the balance moves again.
        StagingSessionResponse third = importService.parseAndStageWithSession(user.getId(), "july.csv", bytes);
        assertThat(third.staging().rows()).allMatch(StagedRow::likelyDuplicate);
        ConfirmResponse thirdResponse = importService.confirmSession(user.getId(), confirmAll(third, account.getId(), true));
        assertThat(thirdResponse.imported()).isEqualTo(3);
        assertThat(thirdResponse.duplicatesDetected()).isZero();

        List<Transaction> afterThird = transactionRepository.findByUserId(user.getId());
        assertThat(afterThird).hasSize(9);
        assertThat(afterThird.stream().filter(t -> t.getIsDuplicateOf() != null)).hasSize(3);
        assertThat(afterThird.stream().filter(t -> t.getNotDuplicateConfirmedAt() != null)).hasSize(3);
        assertThat(balanceOf(account)).isEqualByComparingTo(balanceAfterFirst.add(NET));
    }
}
