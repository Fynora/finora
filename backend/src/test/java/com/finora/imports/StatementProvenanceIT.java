package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.config.BuildVersionResolver;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.entity.StatementImport;
import com.finora.entity.StatementImportExcludedRow;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.StatementImportExcludedRowRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.StatementImportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a statement refresh will need from import time, recorded through the real session confirm
 * against Postgres: the build that parsed the statement, and the rows the user left out -- kept
 * with the statement's own facts for each, and deleted with the statement.
 */
class StatementProvenanceIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private StatementImportService statementImportService;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private StatementImportExcludedRowRepository excludedRowRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;
    @Autowired private BuildVersionResolver buildVersionResolver;
    @Autowired private ImportSessionRepository importSessionRepository;

    private static final byte[] FILE =
            "Date,Description,Amount,Type\n2026-07-01,SAMPLE ROW,1.00,DEBIT\n".getBytes(StandardCharsets.UTF_8);

    private final List<UUID> createdUserIds = new ArrayList<>();

    @AfterEach
    void removeQueuedLearningEvents() {
        if (createdUserIds.isEmpty()) return;
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> createdUserIds.contains(e.getUserId())).toList());
        createdUserIds.clear();
    }

    private StagedRow staged(String description, String amount, int position) {
        return new StagedRow(LocalDate.of(2026, 7, position + 1), description, new BigDecimal(amount), "EXPENSE",
                "Other", "rule", null, false, null, null, null, RowKind.TRANSACTION, null, null, null, null)
                .withRowPosition(position);
    }

    private ConfirmedRow confirmed(StagedRow r, boolean include, boolean likelyDuplicate) {
        return new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(), "Other", include,
                r.categorySource(), r.ruleId(), likelyDuplicate, null, null, false, null, r.rowPosition());
    }

    private Account newUserWithAccount() {
        User user = new User();
        user.setEmail("provenance-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Provenance IT User");
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        createdUserIds.add(user.getId());
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        return accountRepository.save(account);
    }

    @Test
    void confirmRecordsTheParserVersionAndEveryLeftOutRow_andDeletingTheStatementRemovesThem() {
        Account account = newUserWithAccount();
        User user = userRepository.findById(account.getUserId()).orElseThrow();

        StagedRow kept = staged("SAMPLE KEPT", "120.00", 0);
        StagedRow untickedByUser = staged("SAMPLE UNTICKED", "75.50", 1);
        StagedRow flaggedDuplicate = staged("SAMPLE DUPLICATE", "300.00", 2);
        ImportSession session = importSessionService.createSession(
                user.getId(), "statement.csv", FILE, List.of(kept, untickedByUser, flaggedDuplicate), null);

        importService.confirmSession(user.getId(), new ConfirmRequest(session.getId(), List.of(
                confirmed(kept, true, false),
                confirmed(untickedByUser, false, false),
                confirmed(flaggedDuplicate, false, true)),
                account.getId(), null, null, null, null));

        StatementImport statement = statementImportRepository.findAll().stream()
                .filter(s -> s.getUserId().equals(createdUserIds.get(0))).findFirst().orElseThrow();
        assertThat(statement.getParserVersion()).isEqualTo(buildVersionResolver.currentCommit());
        assertThat(statement.getTransactionsSkipped()).isEqualTo(2);

        List<StatementImportExcludedRow> excluded =
                excludedRowRepository.findByStatementImportIdOrderByRowPositionAsc(statement.getId());
        assertThat(excluded).extracting(StatementImportExcludedRow::getRowPosition,
                        StatementImportExcludedRow::getDescription, StatementImportExcludedRow::isLikelyDuplicate)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "SAMPLE UNTICKED", false),
                        org.assertj.core.groups.Tuple.tuple(2, "SAMPLE DUPLICATE", true));
        assertThat(excluded.get(0).getAmount()).isEqualByComparingTo("75.50");
        assertThat(excluded.get(0).getTxnDate()).isEqualTo(LocalDate.of(2026, 7, 2));
        assertThat(excluded.get(0).getTxnType()).isEqualTo("EXPENSE");
        assertThat(excluded).allMatch(r -> r.getUserId().equals(createdUserIds.get(0)));
        // The kept row became a transaction; the left-out ones did not.
        assertThat(transactionRepository.findByStatementImportId(statement.getId()))
                .extracting(com.finora.entity.Transaction::getDescription).containsExactly("SAMPLE KEPT");

        statementImportService.delete(user.getId(), statement.getId(), user.getId());

        assertThat(excludedRowRepository.findByStatementImportIdOrderByRowPositionAsc(statement.getId())).isEmpty();
    }

    @Test
    void aStatementStagedBeforeADeployAndConfirmedAfter_isCreditedToTheBuildThatParsedIt() {
        Account account = newUserWithAccount();
        StagedRow row = staged("SAMPLE KEPT", "120.00", 0);
        ImportSession session = importSessionService.createSession(
                account.getUserId(), "statement.csv", FILE, List.of(row), null);
        // The rows were parsed by an earlier build; a deploy has landed since.
        session.setParserVersion("previousbuild");
        importSessionRepository.save(session);

        importService.confirmSession(account.getUserId(), new ConfirmRequest(session.getId(),
                List.of(confirmed(row, true, false)), account.getId(), null, null, null, null));

        StatementImport statement = statementImportRepository.findAll().stream()
                .filter(s -> s.getUserId().equals(account.getUserId())).findFirst().orElseThrow();
        assertThat(statement.getParserVersion()).isEqualTo("previousbuild");
    }
}
