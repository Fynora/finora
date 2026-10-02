package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.dto.RecurringDto;
import com.finora.entity.Account;
import com.finora.entity.CategoryRule;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.imports.ImportService;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRuleRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The recurring-payment answer end to end on a real Postgres: three monthly "Other" payments to one
 * payee, answered once, then the next import files the payee by the saved rule.
 */
class RecurringAnswerIT extends AbstractIntegrationTest {

    private static final String LABEL = "sample owner";

    @Autowired private ImportService importService;
    @Autowired private RecurringService recurringService;
    @Autowired private RecurringAnswerService recurringAnswerService;
    @Autowired private CategoryRuleRepository ruleRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuthService authService;

    private static String narration(int n, String note) {
        return "UPI-SAMPLE OWNER-sample.owner@okaxis-YESB0XXXXXX-00000000000" + n + "-" + note;
    }

    /**
     * A user with the default categories and three confirmed monthly payments of ₹10,000 to one payee,
     * all "Other". The payee name avoids every keyword-table word ("landlord" alone files a payment
     * as Rent before any rule), so what files the next import is the saved answer.
     */
    private User userWithThreeOtherRentPayments() throws Exception {
        User user = new User();
        user.setEmail("recurring-answer-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Recurring Answer IT User");
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(authService, "seedDefaultCategories", user.getId());
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Recurring Answer IT Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        account = accountRepository.save(account);

        List<ConfirmedRow> rows = List.of(
                new ConfirmedRow(LocalDate.of(2026, 5, 3), narration(1, "RENT"), new BigDecimal("10000.00"), "EXPENSE",
                        "Other", true, "default", null, false, null, null),
                new ConfirmedRow(LocalDate.of(2026, 6, 3), narration(2, "RENT"), new BigDecimal("10000.00"), "EXPENSE",
                        "Other", true, "default", null, false, null, null),
                new ConfirmedRow(LocalDate.of(2026, 7, 3), narration(3, "RENT"), new BigDecimal("10000.00"), "EXPENSE",
                        "Other", true, "default", null, false, null, null));
        importService.confirm(user.getId(),
                new MockMultipartFile("file", "statement.csv", "text/csv",
                        "irrelevant-the-rows-are-supplied-directly".getBytes(StandardCharsets.UTF_8)),
                new ConfirmRequest(null, rows, account.getId(), null, null, null, null));
        return user;
    }

    @Test
    void anAnswer_reFilesThePastPayments_andTheNextImportUsesTheRule_forMoneyGoingOutInRangeOnly() throws Exception {
        User user = userWithThreeOtherRentPayments();
        List<RecurringDto> detected = recurringService.detectForUser(user.getId());
        assertThat(detected).as("precondition: the three payments are detected and asked about")
                .anySatisfy(r -> {
                    assertThat(r.merchant()).isEqualTo(LABEL);
                    assertThat(r.state()).isEqualTo(RecurringDto.QuestionState.NEEDS_ANSWER);
                });

        RecurringAnswerService.Result result = recurringAnswerService.categorize(user.getId(), LABEL, "Rent");

        assertThat(result.refiled()).isEqualTo(3);
        assertThat(recurringService.detectForUser(user.getId()))
                .anySatisfy(r -> {
                    assertThat(r.merchant()).isEqualTo(LABEL);
                    assertThat(r.state()).isEqualTo(RecurringDto.QuestionState.ANSWERED);
                    assertThat(r.answer()).isEqualTo("Rent");
                    assertThat(r.category()).isEqualTo("Rent");
                });

        String csv = "Date,Description,Amount,Type\n"
                + "2026-08-03," + narration(4, "RENT") + ",10000.00,DEBIT\n"
                + "2026-08-04," + narration(5, "DEPOSIT TOP UP") + ",25000.00,DEBIT\n"
                + "2026-08-05," + narration(6, "REFUND") + ",10000.00,CREDIT\n";
        StagingResponse staged = importService.parseAndStage(user.getId(), "next.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        Map<String, StagedRow> byAmountAndType = staged.rows().stream()
                .collect(Collectors.toMap(r -> r.amount().stripTrailingZeros().toPlainString() + "/" + r.type(),
                        Function.identity()));

        StagedRow rent = byAmountAndType.get("10000/EXPENSE");
        assertThat(rent.suggestedCategory()).isEqualTo("Rent");
        assertThat(rent.categorySource()).isEqualTo("user_rule");
        StagedRow outOfRange = byAmountAndType.get("25000/EXPENSE");
        StagedRow incoming = byAmountAndType.get("10000/INCOME");
        assertThat(outOfRange.suggestedCategory())
                .as("out-of-range debit, source=%s rule=%s", outOfRange.categorySource(), outOfRange.ruleId())
                .isNotEqualTo("Rent");
        assertThat(incoming.suggestedCategory())
                .as("credit, source=%s rule=%s", incoming.categorySource(), incoming.ruleId())
                .isNotEqualTo("Rent");
    }

    @Test
    void reFilingBumpsEachRowsVersion_soTheMobileChangeStampMoves() throws Exception {
        User user = userWithThreeOtherRentPayments();
        Map<UUID, Long> before = transactionRepository.findByUserId(user.getId()).stream()
                .collect(Collectors.toMap(Transaction::getId, Transaction::getVersion));

        recurringAnswerService.categorize(user.getId(), LABEL, "Rent");

        assertThat(transactionRepository.findByUserId(user.getId()))
                .hasSize(3)
                .allSatisfy(t -> assertThat(t.getVersion()).isGreaterThan(before.get(t.getId())));
    }

    @Test
    void twoAnswersAtOnce_leaveOneRule_andBothSucceed() throws Exception {
        User user = userWithThreeOtherRentPayments();
        UUID userId = user.getId();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RecurringAnswerService.Result> first = pool.submit(() -> {
                start.await();
                return recurringAnswerService.categorize(userId, LABEL, "Rent");
            });
            Future<RecurringAnswerService.Result> second = pool.submit(() -> {
                start.await();
                return recurringAnswerService.categorize(userId, LABEL, "Rent");
            });
            start.countDown();

            first.get(60, TimeUnit.SECONDS);
            second.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        List<CategoryRule> rules = ruleRepository.findUserPayeeRules(userId);
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).getActionValue()).isEqualTo("Rent");
    }
}
