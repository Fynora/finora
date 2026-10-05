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
    @Autowired private com.finora.repository.CategoryRepository categoryRepository;
    @Autowired private com.finora.transactions.TransactionService transactionService;

    private static String narration(int n, String note) {
        return narration("SAMPLE OWNER", n, note);
    }

    private static String narration(String payee, int n, String note) {
        return "UPI-" + payee + "-" + payee.toLowerCase().replace(' ', '.') + "@okaxis-YESB0XXXXXX-00000000000" + n + "-" + note;
    }

    /**
     * A user with the default categories and three confirmed monthly payments of ₹10,000 to one payee,
     * all "Other". The payee name avoids every keyword-table word ("landlord" alone files a payment
     * as Rent before any rule), so what files the next import is the saved answer.
     */
    private User userWithThreeOtherRentPayments() throws Exception {
        return userWithThreeOtherMonthlyPayments("SAMPLE OWNER");
    }

    /** As {@link #userWithThreeOtherRentPayments}, with three monthly "Other" payments to each payee. */
    private User userWithThreeOtherMonthlyPayments(String... payees) throws Exception {
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

        List<ConfirmedRow> rows = new java.util.ArrayList<>();
        int n = 0;
        for (String payee : payees) {
            for (int month = 5; month <= 7; month++) {
                rows.add(new ConfirmedRow(LocalDate.of(2026, month, 3), narration(payee, ++n, "RENT"),
                        new BigDecimal("10000.00"), "EXPENSE", "Other", true, "default", null, false, null, null));
            }
        }
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

    /**
     * Payee names are editable in both apps, and detection groups payments by the name as stored. A
     * user who tidied "sample owner" into their own name for it was asked about that name, and the
     * answer was saved under it -- but the next import reads the payee from the bank's narration,
     * which still says SAMPLE OWNER, so the answer never applied.
     */
    @Test
    void anAnswerGivenUnderAPayeeNameTheUserEdited_stillFilesTheNextImport() throws Exception {
        User user = userWithThreeOtherRentPayments();
        for (Transaction t : transactionRepository.findByUserId(user.getId())) {
            transactionService.update(user.getId(), t.getId(), new com.finora.transactions.TransactionDto.UpdateRequest(
                    null, null, "My Flat", null, null, null, null, null));
        }
        assertThat(recurringService.detectForUser(user.getId()))
                .as("precondition: asked about the edited name")
                .anySatisfy(r -> assertThat(r.merchant()).isEqualTo("My Flat"));

        RecurringAnswerService.Result result = recurringAnswerService.categorize(user.getId(), "My Flat", "Rent");
        assertThat(result.refiled()).isEqualTo(3);

        String csv = "Date,Description,Amount,Type\n"
                + "2026-08-03," + narration(4, "RENT") + ",10000.00,DEBIT\n";
        StagingResponse staged = importService.parseAndStage(user.getId(), "next.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        StagedRow next = staged.rows().get(0);
        assertThat(next.suggestedCategory()).as("source=%s", next.categorySource()).isEqualTo("Rent");
        assertThat(next.categorySource()).isEqualTo("user_rule");
    }

    /**
     * A person's name can be printed differently on a later payment while their UPI id stays the
     * same (measured on the real statements: 7 payees, mostly people). The answer matches the id.
     */
    @Test
    void anAnswer_filesALaterPaymentToTheSameUpiId_whenTheBankPrintsTheNameDifferently() throws Exception {
        User user = userWithThreeOtherRentPayments();
        recurringAnswerService.categorize(user.getId(), LABEL, "Rent");

        String csv = "Date,Description,Amount,Type\n"
                + "2026-08-03," + narration("S OWNER", 4, "RENT").replace("s.owner@", "sample.owner@") + ",10000.00,DEBIT\n";
        StagingResponse staged = importService.parseAndStage(user.getId(), "next.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        StagedRow next = staged.rows().get(0);
        assertThat(com.finora.util.CategoryRules.extractMerchantLabel(next.description()))
                .as("precondition: the printed name differs").isNotEqualToIgnoringCase(LABEL);
        assertThat(next.suggestedCategory()).as("source=%s", next.categorySource()).isEqualTo("Rent");
        assertThat(next.categorySource()).isEqualTo("user_rule");
    }

    @Test
    void answeringAgain_keepsWhatTheAnswerAlreadyKnewAboutThePayee() throws Exception {
        User user = userWithThreeOtherRentPayments();
        recurringAnswerService.categorize(user.getId(), LABEL, "Rent");
        java.util.List<String> first = ruleRepository.findUserPayeeRule(user.getId(), LABEL).orElseThrow().getPayeeAliases();
        assertThat(first).contains("key:vpa:sample.owner");

        recurringAnswerService.categorize(user.getId(), LABEL, "Rent");

        assertThat(ruleRepository.findUserPayeeRule(user.getId(), LABEL).orElseThrow().getPayeeAliases())
                .containsExactlyInAnyOrderElementsOf(first);
    }

    /** The user renames the payee on its three payments to "My Flat" and answers Rent for it. */
    private User userWhoAnsweredUnderAnEditedName() throws Exception {
        User user = userWithThreeOtherRentPayments();
        for (Transaction t : transactionRepository.findByUserId(user.getId())) {
            transactionService.update(user.getId(), t.getId(), new com.finora.transactions.TransactionDto.UpdateRequest(
                    null, null, "My Flat", null, null, null, null, null));
        }
        recurringAnswerService.categorize(user.getId(), "My Flat", "Rent");
        return user;
    }

    /** Later payments, imported under the name the bank prints, at a new amount the answer doesn't cover. */
    private void confirmLaterPayments(User user, int... months) throws Exception {
        Account account = accountRepository.findByUserId(user.getId()).get(0);
        List<ConfirmedRow> rows = new java.util.ArrayList<>();
        for (int month : months) {
            rows.add(new ConfirmedRow(LocalDate.of(2026, month, 3), narration(month + 10, "RENT"),
                    new BigDecimal("15000.00"), "EXPENSE", "Other", true, "default", null, false, null, null));
        }
        importService.confirm(user.getId(),
                new MockMultipartFile("file", "later.csv", "text/csv", "rows-supplied-directly".getBytes(StandardCharsets.UTF_8)),
                new ConfirmRequest(null, rows, account.getId(), null, null, null, null));
    }

    @Test
    void laterPaymentsUnderTheBanksName_areTheSameAnswer_notANewQuestion() throws Exception {
        User user = userWhoAnsweredUnderAnEditedName();
        confirmLaterPayments(user, 8, 9, 10);

        assertThat(recurringService.detectForUser(user.getId()))
                .as("the bank-named group is the same payee as the answered one")
                .anySatisfy(r -> {
                    assertThat(r.merchant()).isEqualTo(LABEL);
                    assertThat(r.state()).isEqualTo(RecurringDto.QuestionState.AMOUNT_CHANGED);
                    assertThat(r.answer()).isEqualTo("Rent");
                });

        recurringAnswerService.categorize(user.getId(), LABEL, "Rent");

        assertThat(ruleRepository.findUserPayeeRules(user.getId())).as("still one answer").hasSize(1);
        assertThat(ruleRepository.findUserPayeeRules(user.getId()).get(0).getComparisonValue()).isEqualTo("My Flat");
        assertThat(recurringService.detectForUser(user.getId()))
                .anySatisfy(r -> {
                    assertThat(r.merchant()).isEqualTo(LABEL);
                    assertThat(r.state()).isEqualTo(RecurringDto.QuestionState.ANSWERED);
                });
    }

    @Test
    void aLaterPaymentUnderTheBanksName_atANewAmount_isReportedAsChanged() throws Exception {
        User user = userWhoAnsweredUnderAnEditedName();
        confirmLaterPayments(user, 8);

        assertThat(recurringAnswerService.changedAmounts(user.getId()))
                .anySatisfy(c -> {
                    assertThat(c.merchant()).isEqualTo("My Flat");
                    assertThat(c.latestAmount()).isEqualByComparingTo("15000.00");
                });
    }

    /**
     * One payee, one answer. The user answered "sample owner" directly, then renamed its payments and
     * answered again under the new name: the second answer must not also claim the bank's name or UPI
     * id the first one already holds, or which of the two files the next payment would depend on rule
     * order.
     */
    @Test
    void aSecondAnswerUnderARenamedPayee_doesNotTakeTheFirstAnswersNamesForIt() throws Exception {
        User user = userWithThreeOtherRentPayments();
        recurringAnswerService.categorize(user.getId(), LABEL, "Rent");
        for (Transaction t : transactionRepository.findByUserId(user.getId())) {
            transactionService.update(user.getId(), t.getId(), new com.finora.transactions.TransactionDto.UpdateRequest(
                    null, null, "My Flat", null, null, null, null, null));
        }

        recurringAnswerService.categorize(user.getId(), "My Flat", "Rent");

        CategoryRule second = ruleRepository.findUserPayeeRule(user.getId(), "My Flat").orElseThrow();
        assertThat(second.getPayeeAliases()).as("held by the first answer").isEmpty();
        assertThat(ruleRepository.findUserPayeeRule(user.getId(), LABEL).orElseThrow().getPayeeAliases())
                .contains("key:vpa:sample.owner");
    }

    /** A payment to the same UPI id that the bank printed under another name ("S OWNER"). */
    private static String driftedNarration(int n) {
        return narration("S OWNER", n, "RENT").replace("s.owner@", "sample.owner@");
    }

    private void confirmPayment(User user, LocalDate date, String narration, String amount) throws Exception {
        Account account = accountRepository.findByUserId(user.getId()).get(0);
        importService.confirm(user.getId(),
                new MockMultipartFile("file", "one.csv", "text/csv", "rows-supplied-directly".getBytes(StandardCharsets.UTF_8)),
                new ConfirmRequest(null, List.of(new ConfirmedRow(date, narration, new BigDecimal(amount), "EXPENSE",
                        "Other", true, "default", null, false, null, null)), account.getId(), null, null, null, null));
    }

    @Test
    void anAnswer_reFilesAPastPaymentToTheSameUpiId_printedUnderAnotherName() throws Exception {
        User user = userWithThreeOtherRentPayments();
        confirmPayment(user, LocalDate.of(2026, 4, 3), driftedNarration(9), "10000.00");

        recurringAnswerService.categorize(user.getId(), LABEL, "Rent");

        Transaction drifted = transactionRepository.findByUserId(user.getId()).stream()
                .filter(t -> t.getTxnDate().equals(LocalDate.of(2026, 4, 3))).findFirst().orElseThrow();
        assertThat(drifted.getMerchant()).as("precondition: stored under the other name").isNotEqualToIgnoringCase(LABEL);
        assertThat(categoryRepository.findById(drifted.getCategoryId()).orElseThrow().getName()).isEqualTo("Rent");
        assertThat(drifted.getDecisionSource()).isEqualTo(Transaction.DecisionSource.USER_RULE);
    }

    @Test
    void aLaterPaymentToTheSameUpiId_underAnotherName_atANewAmount_isReportedAsChanged() throws Exception {
        User user = userWithThreeOtherRentPayments();
        recurringAnswerService.categorize(user.getId(), LABEL, "Rent");
        confirmPayment(user, LocalDate.of(2026, 8, 3), driftedNarration(9), "15000.00");

        assertThat(recurringAnswerService.changedAmounts(user.getId()))
                .anySatisfy(c -> {
                    assertThat(c.merchant()).isEqualToIgnoringCase(LABEL);
                    assertThat(c.latestAmount()).isEqualByComparingTo("15000.00");
                });
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

    /** Two payees answered at once with the same brand-new category ("Something else"): both save, one category. */
    @Test
    void twoAnswersAtOnce_forTwoPayees_creatingTheSameNewCategory_bothSucceed() throws Exception {
        User user = userWithThreeOtherMonthlyPayments("SAMPLE OWNER", "SAMPLE KEEPER");
        UUID userId = user.getId();
        assertThat(recurringService.detectForUser(userId)).as("precondition: both payees are asked about")
                .filteredOn(r -> r.state() == RecurringDto.QuestionState.NEEDS_ANSWER).hasSize(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RecurringAnswerService.Result> first = pool.submit(() -> {
                start.await();
                return recurringAnswerService.categorize(userId, "sample owner", "Sample Upkeep");
            });
            Future<RecurringAnswerService.Result> second = pool.submit(() -> {
                start.await();
                return recurringAnswerService.categorize(userId, "sample keeper", "Sample Upkeep");
            });
            start.countDown();

            first.get(60, TimeUnit.SECONDS);
            second.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(ruleRepository.findUserPayeeRules(userId)).hasSize(2)
                .allSatisfy(r -> assertThat(r.getActionValue()).isEqualTo("Sample Upkeep"));
        assertThat(categoryRepository.findByUserId(userId))
                .filteredOn(c -> c.getName().equalsIgnoreCase("Sample Upkeep")).hasSize(1);
    }

    /**
     * A rule the user has that also matches the payee (made after the answer, or matching more
     * broadly) does not override the answer: the answer names this payee and an amount range.
     */
    @Test
    void anAnswerOutranksAnOrdinaryRuleOfTheUsersThatAlsoMatches() throws Exception {
        User user = userWithThreeOtherRentPayments();
        recurringAnswerService.categorize(user.getId(), LABEL, "Rent");
        CategoryRule broad = new CategoryRule();
        broad.setUserId(user.getId());
        broad.setScope(CategoryRule.Scope.USER);
        broad.setField(CategoryRule.Field.DESCRIPTION);
        broad.setOperator(CategoryRule.Operator.CONTAINS);
        broad.setComparisonValue("owner");   // sorts before "sample owner" at the same priority
        broad.setActionType(CategoryRule.ActionType.ASSIGN_CATEGORY);
        broad.setActionValue("Shopping");
        broad.setPriority(100);
        ruleRepository.save(broad);

        String csv = "Date,Description,Amount,Type\n" + "2026-08-03," + narration(4, "RENT") + ",10000.00,DEBIT\n";
        StagedRow rent = importService.parseAndStage(user.getId(), "next.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))).rows().get(0);

        assertThat(rent.suggestedCategory()).isEqualTo("Rent");
        assertThat(rent.ruleId()).isEqualTo(ruleRepository.findUserPayeeRule(user.getId(), LABEL).orElseThrow().getId());
    }
}
