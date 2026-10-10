package com.finora.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.MultiAccountConfirmRequest;
import com.finora.dto.ImportDto.NewAccountRequest;
import com.finora.dto.ImportDto.SectionConfirm;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.ImportSession;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.exception.ErrorCode;
import com.finora.imports.jobs.ImportJobWorker;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationStatus;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.provider.EmailNotificationProvider;
import com.finora.notification.repository.NotificationRepository;
import com.finora.notification.template.TemplateRenderer;
import com.finora.notification.worker.NotificationDispatcher;
import com.finora.repository.AccountRepository;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.HeldStatementService;
import com.finora.service.StatementStatusNotifier;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Gate 1 spec §6 and the integration half of §7: however an import is retried -- the same file
 * uploaded again, a held statement re-run, a rejected or failed one uploaded again, a confirm sent
 * twice -- the user's ledger ends up holding each transaction once, and the import always ends in
 * a state its own API shows.
 *
 * <p>Driven the way a user and an operator drive it: real uploads and confirms over HTTP, the real
 * worker ({@code drainOnce}), the real review service and the real notification outbox. Only what
 * no test can produce in one build is put in place by hand, and each such place says so.
 *
 * <p>Every statement here is synthetic. Confirm payloads are built as the apps build them
 * ({@code importReview.ts}'s {@code beginReview} and {@code toConfirmedRows}, the same file in both
 * clients): a row the engine questioned starts unticked and unanswered, and the import button
 * stays disabled until each one is answered "skip" or "import anyway".
 *
 * <p>Storage is on and the pollers are off, as in {@code HeldStatementReuploadIT}.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-import-retry-safety-it",
        "app.import.queue.enabled=false"
})
class ImportRetrySafetyIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private ImportJobRepository jobRepository;
    @Autowired private ImportSessionRepository sessionRepository;
    @Autowired private HeldStatementRepository heldStatementRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private HeldStatementService heldStatementService;
    @Autowired private StatementStatusNotifier statementStatusNotifier;
    @Autowired private ImportJobWorker worker;
    @Autowired private NotificationDispatcher dispatcher;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;

    @MockitoSpyBean private EmailNotificationProvider emailProvider;
    @MockitoSpyBean private TemplateRenderer templateRenderer;

    /** Three ordinary rows, no running balance: stages and completes with nothing to review. */
    private static final String JULY = """
            Date,Description,Amount,Type
            2026-07-10,SWIGGY ORDER,486.00,DEBIT
            2026-07-11,BLINKIT GROCERIES,1240.50,DEBIT
            2026-07-14,SALARY CREDIT TEST EMPLOYER,52000.00,CREDIT
            """;

    /** The same three rows and one more -- a longer download of the same month. */
    private static final String JULY_AND_ONE_MORE = JULY + "2026-07-20,TEST PHARMACY,315.00,DEBIT\n";

    /** A statement period in 2030 is what makes the trust check hold it -- the trigger
     *  {@code HeldStatementReuploadIT} uses. A re-run still holds it: the check is anchored to the
     *  day it was held. */
    private static final String FUTURE_PERIOD = """
            Date,Description,Amount,Balance,Statement Period
            01/01/2026,Opening balance,,1000.00,01/01/2030 to 31/01/2030
            05/01/2026,Coffee shop,-150.00,850.00,
            """;

    /** Nothing in it trips the trust check. */
    private static final String CLEAN_WITH_BALANCE = """
            Date,Description,Amount,Balance
            01/01/2026,Opening balance,,1000.00
            05/01/2026,Coffee shop,-150.00,850.00
            """;

    /** Starts like a PDF and is not one: IMPORT_CORRUPT_PDF, which is never held. */
    private static final byte[] DAMAGED_PDF =
            "%PDF-1.4\nthis is not a real document\n".getBytes(StandardCharsets.UTF_8);

    private enum Answer { SKIP, IMPORT_ANYWAY }

    // ---------------------------------------------------------------------------------------
    // §6.1 The same file again
    // ---------------------------------------------------------------------------------------

    @Test
    void theSameFileConfirmedAgainTheWayTheAppsConfirmItAddsNothing() throws Exception {
        User user = user();
        JsonNode first = stage(user, JULY);
        assertThat(first.get("previousImport").isNull()).isTrue();
        UUID firstSession = sessionIdOf(first);
        assertThat(confirm(user, firstSession, null, Answer.SKIP).getStatusCode()).isEqualTo(HttpStatus.OK);
        Account account = onlyAccountOf(user);
        assertThat(countedTransactionsOf(user)).hasSize(3);
        BigDecimal balance = balanceOf(account);

        JsonNode second = stage(user, JULY);
        assertThat(sessionIdOf(second)).as("a confirmed session is never replayed").isNotEqualTo(firstSession);
        assertThat(second.get("previousImport").get("transactionsImported").asInt())
                .as("the user is told this file was imported before").isEqualTo(3);
        assertThat(second.get("previousImport").get("accountId").asText()).isEqualTo(account.getId().toString());
        assertThat(stagedRowsOf(sessionIdOf(second)))
                .as("every row is put to the user as a question, with the row it matches")
                .hasSize(3).allMatch(row -> row.duplicateMatch() != null);

        ResponseEntity<String> again = confirm(user, sessionIdOf(second), account.getId(), Answer.SKIP);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(again.getBody()).get("data").get("imported").asInt()).isZero();
        assertThat(transactionRepository.findByUserId(user.getId()))
                .as("no row was written at all, counted or marked").hasSize(3);
        assertThat(balanceOf(account)).isEqualByComparingTo(balance);
    }

    @Test
    void aLongerStatementCoveringTheSameDaysAddsOnlyItsNewRow() throws Exception {
        User user = user();
        assertThat(confirm(user, sessionIdOf(stage(user, JULY)), null, Answer.SKIP).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Account account = onlyAccountOf(user);

        JsonNode longer = stage(user, JULY_AND_ONE_MORE);
        assertThat(longer.get("previousImport").isNull())
                .as("different bytes: not the same file, so no notice").isTrue();
        List<StagedRow> rows = stagedRowsOf(sessionIdOf(longer));
        assertThat(rows).hasSize(4);
        assertThat(rows.stream().filter(row -> row.duplicateMatch() != null)).hasSize(3);

        assertThat(confirm(user, sessionIdOf(longer), account.getId(), Answer.SKIP).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        List<Transaction> all = transactionRepository.findByUserId(user.getId());
        assertThat(all).hasSize(4);
        assertThat(all).extracting(Transaction::getDescription).filteredOn(d -> d.contains("PHARMACY")).hasSize(1);
        assertThat(all).noneMatch(t -> t.getIsDuplicateOf() != null);
    }

    /**
     * One file through both doors before anything is confirmed -- the direct upload twice, then the
     * queue. One set of rows is staged, so there is one review and one import; this is the shape
     * that once became two sessions and two imports of the same statement.
     */
    @Test
    void theSameFileThroughTheDirectUploadAndTheQueueIsOneReviewAndOneImport() throws Exception {
        User user = user();
        UUID sessionId = sessionIdOf(stage(user, JULY));
        assertThat(sessionIdOf(stage(user, JULY))).as("a second click replays the first read").isEqualTo(sessionId);

        UUID jobId = upload(user, JULY, "statement.csv");
        worker.drainOnce();

        JsonNode progress = progress(user, jobId);
        assertThat(progress.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(progress.get("importSessionId").asText()).isEqualTo(sessionId.toString());
        assertThat(sessionRepository.findByUserIdOrderByCreatedAtDesc(user.getId())).hasSize(1);

        assertThat(confirm(user, sessionId, null, Answer.SKIP).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirm(user, sessionId, null, Answer.SKIP).getStatusCode())
                .as("opening the review again from the job").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(transactionRepository.findByUserId(user.getId())).hasSize(3);
        assertThat(statementImportsOf(user)).isEqualTo(1);
    }

    /** The queue's door, for a file already imported: the notice and the questions are on the
     *  review the job opens, exactly as on the direct upload's. */
    @Test
    void aQueuedUploadOfAnImportedFileSaysSoAndAddsNothingWhenSkipped() throws Exception {
        User user = user();
        assertThat(confirm(user, sessionIdOf(stage(user, JULY)), null, Answer.SKIP).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Account account = onlyAccountOf(user);

        UUID jobId = upload(user, JULY, "statement.csv");
        worker.drainOnce();

        JsonNode progress = progress(user, jobId);
        assertThat(progress.get("status").asText()).isEqualTo("COMPLETED");
        UUID sessionId = UUID.fromString(progress.get("importSessionId").asText());
        JsonNode review = mapper.readTree(get(user, "/api/v1/import/sessions/" + sessionId).getBody()).get("data");
        assertThat(review.get("previousImport").get("transactionsImported").asInt()).isEqualTo(3);
        assertThat(stagedRowsOf(sessionId)).hasSize(3).allMatch(row -> row.duplicateMatch() != null);

        assertThat(confirm(user, sessionId, account.getId(), Answer.SKIP).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(transactionRepository.findByUserId(user.getId())).hasSize(3);
    }

    /** The PDF door reads a different format into the same review, so the same file again is the
     *  same question there. */
    @Test
    void aPdfStatementUploadedAgainAndSkippedAddsNothing() throws Exception {
        User user = user();
        byte[] pdf = PdfFixtureBuilder.buildSingularDepositWithdrawalColumnsSample();
        JsonNode first = stagePdf(user, pdf);
        assertThat(first.get("multiAccount").asBoolean()).isFalse();
        assertThat(first.get("previousImport").isNull()).isTrue();
        UUID firstSession = sessionIdOf(first);
        int staged = stagedRowsOf(firstSession).size();
        assertThat(staged).isEqualTo(3);
        assertThat(confirm(user, firstSession, null, Answer.SKIP).getStatusCode()).isEqualTo(HttpStatus.OK);
        Account account = onlyAccountOf(user);
        BigDecimal balance = balanceOf(account);

        JsonNode second = stagePdf(user, pdf);
        assertThat(second.get("previousImport").get("transactionsImported").asInt()).isEqualTo(staged);
        assertThat(stagedRowsOf(sessionIdOf(second))).hasSize(staged).allMatch(row -> row.duplicateMatch() != null);
        assertThat(confirm(user, sessionIdOf(second), account.getId(), Answer.SKIP).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(transactionRepository.findByUserId(user.getId())).hasSize(staged);
        assertThat(balanceOf(account)).isEqualByComparingTo(balance);
    }

    /**
     * The other answer to the question: the user says these really are separate transactions. They
     * are added because the user said so, and that is the only way a second copy gets in.
     */
    @Test
    void onlyTheUsersOwnImportAnywayAnswerAddsTheRowsASecondTime() throws Exception {
        User user = user();
        assertThat(confirm(user, sessionIdOf(stage(user, JULY)), null, Answer.SKIP).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Account account = onlyAccountOf(user);

        UUID second = sessionIdOf(stage(user, JULY));
        assertThat(confirm(user, second, account.getId(), Answer.IMPORT_ANYWAY).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        List<Transaction> all = transactionRepository.findByUserId(user.getId());
        assertThat(all).hasSize(6);
        assertThat(all.stream().filter(t -> t.getNotDuplicateConfirmedAt() != null))
                .as("each second copy carries the user's decision").hasSize(3);
    }

    // ---------------------------------------------------------------------------------------
    // §6.5 One session, confirmed more than once
    // ---------------------------------------------------------------------------------------

    @Test
    void twoConfirmsSentAtTheSameMomentImportTheStatementOnce() throws Exception {
        User user = user();
        UUID sessionId = sessionIdOf(stage(user, JULY));
        ConfirmRequest request = confirmRequest(sessionId, null, Answer.SKIP);
        assertOneImportedAndOneRefused(postTwiceAtOnce(user, "/api/v1/import/csv/confirm", request));
        assertThat(transactionRepository.findByUserId(user.getId())).hasSize(3);
        assertThat(statementImportsOf(user)).isEqualTo(1);
        assertThat(accountRepository.findByUserId(user.getId())).hasSize(1);
    }

    /**
     * A statement covering two accounts is confirmed by its own endpoint, with its own claim. The
     * session is staged by hand, as {@code MultiSectionSharedTransferIT} stages one: no synthetic
     * PDF here reads as two accounts.
     */
    @Test
    void aStatementCoveringTwoAccountsConfirmedTwiceAtOnceIsImportedOnce() throws Exception {
        User user = user();
        Account savings = account(user, "Savings", Account.Type.SAVINGS);
        Account card = account(user, "Credit Card", Account.Type.CREDIT_CARD);
        LocalDate when = LocalDate.of(2026, 7, 10);
        BigDecimal amount = new BigDecimal("30000.00");
        ImportSession session = importSessionService.createMultiSection(user.getId(), "consolidated.pdf",
                "synthetic consolidated statement".getBytes(StandardCharsets.UTF_8), List.of(
                        new StagedAccountSection(null, List.of(new StagedRow(when, "CREDIT CARD PAYMENT", amount,
                                "EXPENSE", "Other", "rule", null, false, null, null)), 1, 0, List.of()),
                        new StagedAccountSection(null, List.of(new StagedRow(when, "PAYMENT RECEIVED THANK YOU",
                                amount, "INCOME", "Other", "rule", null, false, null, null)), 1, 0, List.of())));
        MultiAccountConfirmRequest request = new MultiAccountConfirmRequest(session.getId(), List.of(
                new SectionConfirm(List.of(new ConfirmedRow(when, "CREDIT CARD PAYMENT", amount, "EXPENSE",
                        "Other", true, "rule", null, false, null, null, false)), savings.getId(), null, null, null),
                new SectionConfirm(List.of(new ConfirmedRow(when, "PAYMENT RECEIVED THANK YOU", amount, "INCOME",
                        "Other", true, "rule", null, false, null, null, false)), card.getId(), null, null, null)));

        assertOneImportedAndOneRefused(postTwiceAtOnce(user, "/api/v1/import/pdf/confirm-multi", request));
        assertThat(post(user, "/api/v1/import/pdf/confirm-multi", request).getStatusCode())
                .as("and once more, after it is done").isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(transactionRepository.findByUserId(user.getId())).hasSize(2);
        assertThat(statementImportsOf(user)).as("one statement per account, not per request").isEqualTo(2);
    }

    @Test
    void aConfirmSentAgainAfterItSucceededIsRefusedAndAddsNothing() throws Exception {
        User user = user();
        UUID sessionId = sessionIdOf(stage(user, JULY));
        ConfirmRequest request = confirmRequest(sessionId, null, Answer.SKIP);
        assertThat(post(user, "/api/v1/import/csv/confirm", request).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> again = post(user, "/api/v1/import/csv/confirm", request);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mapper.readTree(again.getBody()).get("errorCode").asText())
                .isEqualTo(ErrorCode.IMPORT_SESSION_ALREADY_CONFIRMED.code());
        assertThat(transactionRepository.findByUserId(user.getId())).hasSize(3);
        assertThat(statementImportsOf(user)).isEqualTo(1);
    }

    /**
     * A confirm that is refused after it has claimed the session -- here, rows that are not the
     * rows that were staged -- must give the claim back with everything else, or the user's one
     * retry would be told "already confirmed" about an import that never happened.
     */
    @Test
    void aConfirmThatIsRefusedLeavesNothingBehindAndTheSameSessionStillConfirms() throws Exception {
        User user = user();
        UUID sessionId = sessionIdOf(stage(user, JULY));
        ConfirmRequest good = confirmRequest(sessionId, null, Answer.SKIP);
        List<ConfirmedRow> tampered = new ArrayList<>(good.rows());
        ConfirmedRow r = tampered.get(0);
        tampered.set(0, new ConfirmedRow(r.date(), r.description(), r.amount().add(BigDecimal.ONE), r.type(),
                r.category(), r.include(), r.categorySource(), r.ruleId(), r.likelyDuplicate(),
                r.referenceNumber(), r.balanceAfter(), r.confirmedNotDuplicate(), r.categoryConfidence(),
                r.rowPosition(), r.international(), r.foreignCurrency(), r.foreignAmount()));

        ResponseEntity<String> refused = post(user, "/api/v1/import/csv/confirm", good.withRows(tampered));

        assertThat(refused.getStatusCode().is4xxClientError()).isTrue();
        assertThat(transactionRepository.findByUserId(user.getId())).isEmpty();
        assertThat(accountRepository.findByUserId(user.getId())).isEmpty();
        assertThat(statementImportsOf(user)).isZero();
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getStatus())
                .isEqualTo(ImportSession.STATUS_STAGED);

        assertThat(post(user, "/api/v1/import/csv/confirm", good).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(transactionRepository.findByUserId(user.getId())).hasSize(3);
        assertThat(statementImportsOf(user)).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------
    // §6.2 A held statement re-run, then approved
    // ---------------------------------------------------------------------------------------

    @Test
    void aHeldStatementReRunThenApprovedIsImportedExactlyOnce() throws Exception {
        User user = user();
        UUID jobId = upload(user, FUTURE_PERIOD, "statement.csv");
        worker.drainOnce();
        ImportJob held = jobRepository.findById(jobId).orElseThrow();
        assertThat(held.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_TRUST_REVIEW);
        String heldId = heldIdOf(jobId);
        assertThat(confirm(user, held.getImportSessionId(), null, Answer.SKIP).getStatusCode())
                .as("held rows cannot be confirmed").isEqualTo(HttpStatus.CONFLICT);

        UUID admin = user().getId();
        assertThat(heldStatementService.rerunParser(admin, heldId).stillHeld()).isTrue();
        assertThat(heldStatementService.rerunParser(admin, heldId).stillHeld())
                .as("re-running twice changes nothing").isTrue();
        assertThat(upload(user, FUTURE_PERIOD, "statement.csv"))
                .as("uploading it again while it is being checked follows the same job").isEqualTo(jobId);
        heldStatementService.approve(admin, heldId, null, null);

        JsonNode progress = progress(user, jobId);
        assertThat(progress.get("status").asText()).isEqualTo("COMPLETED");
        UUID sessionId = UUID.fromString(progress.get("importSessionId").asText());
        int staged = stagedRowsOf(sessionId).size();
        assertThat(staged).isPositive();

        assertThat(confirm(user, sessionId, null, Answer.SKIP).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirm(user, sessionId, null, Answer.SKIP).getStatusCode())
                .as("the second confirm of the released rows").isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(transactionRepository.findByUserId(user.getId())).hasSize(staged);
        assertThat(statementImportsOf(user)).isEqualTo(1);
        assertThat(jobsOf(user)).hasSize(1);
    }

    /**
     * The re-run that clears: this build reads the statement cleanly, so its rows replace the ones
     * staged when held, and approving releases those. One set of rows exists to confirm -- the
     * replaced session is gone, not left behind as a second confirmable copy.
     *
     * <p>The hold is put in place by hand ({@code holdForTrustReview}, then the operator's own
     * "open the missing review"), because one set of bytes cannot both trip the check and clear it
     * in one build.
     */
    @Test
    void aReRunThatReplacesTheHeldRowsLeavesExactlyOneSetToConfirm() throws Exception {
        User user = user();
        UUID admin = user().getId();
        ImportJob held = cleanStatementHeldWithAReview(user, admin);
        UUID heldSession = held.getImportSessionId();
        String heldId = heldIdOf(held.getId());

        assertThat(heldStatementService.rerunParser(admin, heldId).stillHeld()).isFalse();
        UUID restaged = jobRepository.findById(held.getId()).orElseThrow().getImportSessionId();
        assertThat(restaged).isNotEqualTo(heldSession);
        assertThat(sessionRepository.findById(heldSession)).as("the rows that were held are gone").isEmpty();
        heldStatementService.approve(admin, heldId, null, null);

        assertThat(confirm(user, heldSession, null, Answer.SKIP).getStatusCode())
                .as("the replaced session cannot be confirmed").isEqualTo(HttpStatus.NOT_FOUND);
        int staged = stagedRowsOf(restaged).size();
        assertThat(confirm(user, restaged, null, Answer.SKIP).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(transactionRepository.findByUserId(user.getId())).hasSize(staged).isNotEmpty();
        assertThat(statementImportsOf(user)).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------
    // §6.3 A rejected statement uploaded again
    // ---------------------------------------------------------------------------------------

    /** Under the build that read it wrongly, the same file gets the same answer -- and the answer
     *  is shown, not a second review and not an import. */
    @Test
    void aRejectedStatementUploadedAgainUnchangedFailsTheSameWayAndAddsNothing() throws Exception {
        User user = user();
        UUID jobId = upload(user, FUTURE_PERIOD, "statement.csv");
        worker.drainOnce();
        heldStatementService.reject(user().getId(), heldIdOf(jobId), "rows do not match the document");

        UUID againId = upload(user, FUTURE_PERIOD, "statement.csv");
        worker.drainOnce();

        assertThat(againId).isNotEqualTo(jobId);
        JsonNode again = progress(user, againId);
        assertThat(again.get("status").asText()).isEqualTo("FAILED");
        assertThat(again.get("error").asText()).isEqualTo(ErrorCode.IMPORT_TRUST_REVIEW_REJECTED.defaultMessage());
        assertThat(confirm(user, jobRepository.findById(againId).orElseThrow().getImportSessionId(), null,
                Answer.SKIP).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(transactionRepository.findByUserId(user.getId())).isEmpty();
        assertThat(heldStatementRepository.findByImportJobId(againId)).as("no second review").isEmpty();
    }

    /**
     * After a fix ships, the same file is read afresh rather than replayed from the rejected rows,
     * imports once, and the old failure leaves the user's list (#2058).
     *
     * <p>"A fix shipped" is a session staged by an older build, as {@code HeldStatementReuploadIT}
     * models a deploy; the hold itself is put in place by hand for the reason given on
     * {@link #aReRunThatReplacesTheHeldRowsLeavesExactlyOneSetToConfirm}.
     */
    @Test
    void aRejectedStatementUploadedAgainAfterAFixImportsOnceAndTheOldFailureLeavesTheList() throws Exception {
        User user = user();
        UUID admin = user().getId();
        ImportJob rejected = cleanStatementHeldWithAReview(user, admin);
        heldStatementService.reject(admin, heldIdOf(rejected.getId()), "rows do not match the document");
        assertThat(recentJobIdsOf(user)).containsExactly(rejected.getId());
        markSessionsStagedByAnOlderBuild(user);

        UUID againId = upload(user, CLEAN_WITH_BALANCE, "statement.csv");
        worker.drainOnce();

        assertThat(againId).isNotEqualTo(rejected.getId());
        JsonNode again = progress(user, againId);
        assertThat(again.get("status").asText()).isEqualTo("COMPLETED");
        UUID sessionId = UUID.fromString(again.get("importSessionId").asText());
        assertThat(sessionId).as("read afresh, not the rejected rows").isNotEqualTo(rejected.getImportSessionId());
        int staged = stagedRowsOf(sessionId).size();
        assertThat(recentJobIdsOf(user))
                .as("until it is imported the failure is still listed, with the new upload")
                .containsExactlyInAnyOrder(againId, rejected.getId());

        assertThat(confirm(user, sessionId, null, Answer.SKIP).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(transactionRepository.findByUserId(user.getId())).hasSize(staged).isNotEmpty();
        assertThat(statementImportsOf(user)).isEqualTo(1);
        assertThat(recentJobIdsOf(user)).as("the old failure is hidden once the file is imported")
                .containsExactly(againId);
    }

    // ---------------------------------------------------------------------------------------
    // §6.4 and §7 lifecycle 3 and 4: an import held for the parser, then reprocessed
    // ---------------------------------------------------------------------------------------

    @Test
    void aHeldImportThatReadsAfterAFixGoesBackToReviewAndConfirmsOnce() throws Exception {
        User user = user();
        UUID jobId = upload(user, JULY, "statement.csv");
        holdAsTheWorkerWould(jobId);
        assertThat(progress(user, jobId).get("status").asText()).isEqualTo("HELD_FOR_REVIEW");

        User admin = adminUser();
        assertThat(reprocess(admin, jobId).getStatusCode()).isEqualTo(HttpStatus.OK);
        worker.drainOnce();

        JsonNode progress = progress(user, jobId);
        assertThat(progress.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(progress.get("error").isNull()).isTrue();
        UUID sessionId = UUID.fromString(progress.get("importSessionId").asText());
        ResponseEntity<String> review = get(user, "/api/v1/import/sessions/" + sessionId);
        assertThat(review.getStatusCode()).as("the review opens from the job").isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(review.getBody()).get("data").get("staging").get("rows")).hasSize(3);
        assertThat(notificationRepository.findByNotificationKey("IMPORT_READY_" + jobId + ":PUSH"))
                .as("the user who was told to wait is told it is ready").isPresent();

        assertThat(reprocess(admin, jobId).getStatusCode())
                .as("a second reprocess of a job no longer held").isEqualTo(HttpStatus.CONFLICT);
        assertThat(worker.drainOnce()).isZero();
        assertThat(confirm(user, sessionId, null, Answer.SKIP).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(transactionRepository.findByUserId(user.getId())).hasSize(3);
        assertThat(statementImportsOf(user)).isEqualTo(1);
    }

    @Test
    void aHeldImportThatFailsAgainShowsItsReasonTellsTheUserAndCanBeDismissed() throws Exception {
        User user = user();
        UUID jobId = upload(user, DAMAGED_PDF, "statement.pdf");
        holdAsTheWorkerWould(jobId);

        assertThat(reprocess(adminUser(), jobId).getStatusCode()).isEqualTo(HttpStatus.OK);
        worker.drainOnce();

        JsonNode progress = progress(user, jobId);
        assertThat(progress.get("status").asText()).isEqualTo("FAILED");
        assertThat(progress.get("error").asText()).isEqualTo(ErrorCode.IMPORT_CORRUPT_PDF.defaultMessage());
        assertThat(progress.get("importSessionId").isNull()).isTrue();
        assertThat(notificationsOf(user, NotificationType.IMPORT_STATEMENT_RESOLVED))
                .as("told by push and by email that it could not be imported")
                .extracting(Notification::getChannel)
                .containsExactlyInAnyOrder(NotificationChannel.PUSH, NotificationChannel.EMAIL);
        assertThat(notificationsOf(user, NotificationType.IMPORT_STATEMENT_RESOLVED))
                .allMatch(n -> n.getNotificationKey().startsWith("IMPORT_FAILED_" + jobId + ":"));
        assertThat(transactionRepository.findByUserId(user.getId())).isEmpty();

        assertThat(recentJobIdsOf(user)).containsExactly(jobId);
        assertThat(post(user, "/api/v1/import/jobs/" + jobId + "/dismiss", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(recentJobIdsOf(user)).isEmpty();
    }

    /**
     * The retry path for a failed import is a new upload (spec §3.1). Here an operator closed a
     * held import with a message; the user uploads the file again once it can be read.
     */
    @Test
    void aFailedImportIsRetriedByUploadingAgainAndConfirmsOnce() throws Exception {
        User user = user();
        UUID failedId = upload(user, JULY, "statement.csv");
        holdAsTheWorkerWould(failedId);
        ResponseEntity<String> resolved = post(adminUser(), "/api/v1/admin/held-imports/" + failedId + "/resolve",
                Map.of("message", "We could not read this one. Please upload it again."));
        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode failed = progress(user, failedId);
        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("error").asText()).isEqualTo("We could not read this one. Please upload it again.");

        UUID retryId = upload(user, JULY, "statement.csv");
        worker.drainOnce();

        assertThat(retryId).as("a failed import does not swallow the next upload").isNotEqualTo(failedId);
        JsonNode retry = progress(user, retryId);
        assertThat(retry.get("status").asText()).isEqualTo("COMPLETED");
        UUID sessionId = UUID.fromString(retry.get("importSessionId").asText());
        assertThat(jobRepository.findById(failedId).orElseThrow().getStatus())
                .as("the worker left the failed job alone").isEqualTo(ImportJob.Status.FAILED);
        assertThat(confirm(user, sessionId, null, Answer.SKIP).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(transactionRepository.findByUserId(user.getId())).hasSize(3);
        assertThat(statementImportsOf(user)).isEqualTo(1);
        assertThat(recentJobIdsOf(user)).as("the failure is hidden once its file is imported")
                .containsExactly(retryId);
    }

    // ---------------------------------------------------------------------------------------
    // §7 lifecycle 6: the notification fails
    // ---------------------------------------------------------------------------------------

    /**
     * Delivery fails on both channels -- no push provider is configured under test, and the email
     * provider throws -- and the decision is still what the user's own screens show: an approved
     * statement opens for review and imports, a rejected one shows its reason.
     */
    @Test
    void whenDeliveryOfTheNotificationFailsTheUsersScreensStillShowTheDecision() throws Exception {
        UUID admin = user().getId();
        User approvedUser = user();
        UUID approvedJob = upload(approvedUser, FUTURE_PERIOD, "statement.csv");
        User rejectedUser = user();
        UUID rejectedJob = upload(rejectedUser, FUTURE_PERIOD, "statement.csv");
        worker.drainOnce();
        doReturn(true).when(emailProvider).isConfigured();
        doThrow(new IllegalStateException("mail provider unreachable")).when(emailProvider).send(any());

        heldStatementService.approve(admin, heldIdOf(approvedJob), null, null);
        heldStatementService.reject(admin, heldIdOf(rejectedJob), "rows do not match the document");
        dispatcher.drainOnce();

        verify(emailProvider, atLeast(2)).send(any());
        for (User told : List.of(approvedUser, rejectedUser)) {
            List<Notification> sent = notificationRepository.findByUserIdOrderByCreatedAtDesc(told.getId());
            assertThat(sent).as("the decision was queued for delivery").isNotEmpty();
            assertThat(sent).as("and none of it was delivered")
                    .noneMatch(n -> n.getStatus() == NotificationStatus.SENT);
        }

        JsonNode approved = progress(approvedUser, approvedJob);
        assertThat(approved.get("status").asText()).isEqualTo("COMPLETED");
        UUID sessionId = UUID.fromString(approved.get("importSessionId").asText());
        int staged = stagedRowsOf(sessionId).size();
        assertThat(confirm(approvedUser, sessionId, null, Answer.SKIP).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(transactionRepository.findByUserId(approvedUser.getId())).hasSize(staged).isNotEmpty();

        JsonNode rejected = progress(rejectedUser, rejectedJob);
        assertThat(rejected.get("status").asText()).isEqualTo("FAILED");
        assertThat(rejected.get("error").asText())
                .isEqualTo(ErrorCode.IMPORT_TRUST_REVIEW_REJECTED.defaultMessage());
        assertThat(recentJobIdsOf(rejectedUser)).containsExactly(rejectedJob);
        assertThat(transactionRepository.findByUserId(rejectedUser.getId())).isEmpty();
    }

    /** One step earlier: the message cannot even be written to the outbox. The operator's decision
     *  still commits -- a notification is never allowed to undo it -- and the screens show it. */
    @Test
    void whenTheNotificationCannotBeQueuedTheDecisionStillStands() throws Exception {
        UUID admin = user().getId();
        User approvedUser = user();
        UUID approvedJob = upload(approvedUser, FUTURE_PERIOD, "statement.csv");
        User rejectedUser = user();
        UUID rejectedJob = upload(rejectedUser, FUTURE_PERIOD, "statement.csv");
        worker.drainOnce();
        // The "we're checking it" messages from the hold itself; what is asserted below is that the
        // decisions add nothing.
        jdbc.update("DELETE FROM notifications WHERE user_id IN (?, ?)", approvedUser.getId(), rejectedUser.getId());
        doThrow(new IllegalStateException("template store unreachable")).when(templateRenderer)
                .render(any(), any(), any());

        heldStatementService.approve(admin, heldIdOf(approvedJob), null, null);
        heldStatementService.reject(admin, heldIdOf(rejectedJob), "rows do not match the document");

        assertThat(notificationRepository.findByUserIdOrderByCreatedAtDesc(approvedUser.getId())).isEmpty();
        assertThat(notificationRepository.findByUserIdOrderByCreatedAtDesc(rejectedUser.getId())).isEmpty();
        assertThat(progress(approvedUser, approvedJob).get("status").asText()).isEqualTo("COMPLETED");
        assertThat(heldStatementRepository.findByImportJobId(approvedJob).orElseThrow().getStatus())
                .isEqualTo(HeldStatement.Status.IMPORTED);
        JsonNode rejected = progress(rejectedUser, rejectedJob);
        assertThat(rejected.get("status").asText()).isEqualTo("FAILED");
        assertThat(rejected.get("error").asText())
                .isEqualTo(ErrorCode.IMPORT_TRUST_REVIEW_REJECTED.defaultMessage());
    }

    // ---------------------------------------------------------------------------------------
    // Driving the app
    // ---------------------------------------------------------------------------------------

    private User user() {
        return saveUser(null);
    }

    private User adminUser() {
        return saveUser("ADMIN");
    }

    private User saveUser(String role) {
        User user = new User();
        user.setEmail("import-retry-safety-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Import Retry Safety IT User");
        user.setPhoneVerified(true);
        if (role != null) {
            user.setRole(role);
            user.setAccountScope(User.SCOPE_ADMIN);
        }
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    private HttpHeaders jsonBearerFor(User user) {
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> get(User user, String path) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
    }

    private ResponseEntity<String> post(User user, String path, Object body) {
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, jsonBearerFor(user)), String.class);
    }

    /** The same request from two threads released together -- a double click, or a retry racing
     *  the request it is retrying. */
    private List<ResponseEntity<String>> postTwiceAtOnce(User user, String path, Object body) throws Exception {
        HttpEntity<Object> entity = new HttpEntity<>(body, jsonBearerFor(user));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<ResponseEntity<String>> responses = new ArrayList<>();
        try {
            List<Future<ResponseEntity<String>>> sent = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                sent.add(pool.submit(() -> {
                    start.await(30, TimeUnit.SECONDS);
                    return restTemplate.exchange(path, HttpMethod.POST, entity, String.class);
                }));
            }
            start.countDown();
            for (Future<ResponseEntity<String>> response : sent) {
                responses.add(response.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        return responses;
    }

    /** One request imported; the other told so. 409 when it waited on the winner's claim, 400
     *  ({@code IMPORT_012}) when it arrived after the winner had committed. */
    private static void assertOneImportedAndOneRefused(List<ResponseEntity<String>> responses) {
        List<Integer> statuses = responses.stream().map(r -> r.getStatusCode().value()).sorted().toList();
        assertThat(statuses).as("one import, and the other request told it was already done")
                .isIn(List.of(200, 409), List.of(200, 400));
        ResponseEntity<String> refused = responses.stream()
                .filter(r -> r.getStatusCode() != HttpStatus.OK).findFirst().orElseThrow();
        assertThat(refused.getBody()).contains("already been");
    }

    private HttpEntity<MultiValueMap<String, Object>> fileUpload(User user, byte[] content, String fileName) {
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override public String getFilename() { return fileName; }
        });
        return new HttpEntity<>(body, headers);
    }

    /** The direct upload: {@code POST /import/csv/stage}. Returns the response's {@code data}. */
    private JsonNode stage(User user, String csv) throws Exception {
        ResponseEntity<String> staged = restTemplate.exchange("/api/v1/import/csv/stage", HttpMethod.POST,
                fileUpload(user, csv.getBytes(StandardCharsets.UTF_8), "statement.csv"), String.class);
        assertThat(staged.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(staged.getBody()).get("data");
        assertThat(data.get("heldForReviewJobId").isNull()).isTrue();
        return data;
    }

    /** The direct PDF upload: {@code POST /import/pdf/stage}. Returns the response's {@code data}. */
    private JsonNode stagePdf(User user, byte[] pdf) throws Exception {
        ResponseEntity<String> staged = restTemplate.exchange("/api/v1/import/pdf/stage", HttpMethod.POST,
                fileUpload(user, pdf, "statement.pdf"), String.class);
        assertThat(staged.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(staged.getBody()).get("data");
        assertThat(data.get("heldForReviewJobId").isNull()).isTrue();
        return data;
    }

    private static UUID sessionIdOf(JsonNode staged) {
        return UUID.fromString(staged.get("sessionId").asText());
    }

    /** The queued upload: {@code POST /import/jobs}. */
    private UUID upload(User user, String csv, String fileName) throws Exception {
        return upload(user, csv.getBytes(StandardCharsets.UTF_8), fileName);
    }

    private UUID upload(User user, byte[] content, String fileName) throws Exception {
        ResponseEntity<String> accepted = restTemplate.exchange("/api/v1/import/jobs", HttpMethod.POST,
                fileUpload(user, content, fileName), String.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return UUID.fromString(mapper.readTree(accepted.getBody()).get("data").get("jobId").asText());
    }

    private JsonNode progress(User user, UUID jobId) throws Exception {
        ResponseEntity<String> response = get(user, "/api/v1/import/jobs/" + jobId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    /** {@code GET /import/jobs}: what the user's own recent-imports list shows. */
    private List<UUID> recentJobIdsOf(User user) throws Exception {
        ResponseEntity<String> response = get(user, "/api/v1/import/jobs");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<UUID> ids = new ArrayList<>();
        mapper.readTree(response.getBody()).get("data")
                .forEach(job -> ids.add(UUID.fromString(job.get("jobId").asText())));
        return ids;
    }

    private ResponseEntity<String> reprocess(User admin, UUID jobId) {
        return post(admin, "/api/v1/admin/held-imports/" + jobId + "/reprocess", null);
    }

    private List<StagedRow> stagedRowsOf(UUID sessionId) {
        return importSessionService.readStagedRows(sessionRepository.findById(sessionId).orElseThrow());
    }

    /**
     * The confirm payload either app sends for this session: every row the engine did not question
     * is included, and every row it did carries the user's answer.
     */
    private ConfirmRequest confirmRequest(UUID sessionId, UUID existingAccountId, Answer answer) {
        ImportSession session = sessionRepository.findById(sessionId).orElseThrow();
        DetectedAccountInfo detected = importSessionService.readDetectedAccount(session);
        List<ConfirmedRow> rows = importSessionService.readStagedRows(session).stream().map(row -> {
            boolean asked = row.duplicateMatch() != null;
            boolean importAnyway = asked && answer == Answer.IMPORT_ANYWAY;
            return new ConfirmedRow(row.date(), row.description(), row.amount(), row.type(),
                    row.suggestedCategory() == null ? "Other" : row.suggestedCategory(),
                    !asked || importAnyway, row.categorySource(), row.ruleId(), row.likelyDuplicate(),
                    row.referenceNumber(), row.balanceAfter(), importAnyway, row.categoryConfidence(),
                    row.rowPosition(), row.international(), row.foreignCurrency(), row.foreignAmount());
        }).toList();
        NewAccountRequest account = existingAccountId != null ? null
                : new NewAccountRequest("Retry safety IT account", "SAVINGS", null, null, null,
                        null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null);
        return new ConfirmRequest(sessionId, rows, existingAccountId, account, null, null, null,
                detected == null ? null : detected.statementPeriodStart(),
                detected == null ? null : detected.statementPeriodEnd(),
                null, null, null, null);
    }

    /** {@code POST /import/csv/confirm}. A session that no longer exists is answered as the
     *  endpoint answers it, with nothing to build rows from. */
    private ResponseEntity<String> confirm(User user, UUID sessionId, UUID existingAccountId, Answer answer) {
        ConfirmRequest request = sessionRepository.existsById(sessionId)
                ? confirmRequest(sessionId, existingAccountId, answer)
                : new ConfirmRequest(sessionId, List.of(), existingAccountId, null, null, null, null);
        return post(user, "/api/v1/import/csv/confirm", request);
    }

    // ---------------------------------------------------------------------------------------
    // Reading the result
    // ---------------------------------------------------------------------------------------

    private Account account(User owner, String name, Account.Type type) {
        Account account = new Account();
        account.setUserId(owner.getId());
        account.setName(name);
        account.setAccountType(type);
        account.setBalance(BigDecimal.ZERO);
        return accountRepository.save(account);
    }

    private Account onlyAccountOf(User user) {
        List<Account> accounts = accountRepository.findByUserId(user.getId());
        assertThat(accounts).hasSize(1);
        return accounts.get(0);
    }

    private BigDecimal balanceOf(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getBalance();
    }

    /** The user's transactions that count: not marked as a copy of another. */
    private List<Transaction> countedTransactionsOf(User user) {
        return transactionRepository.findByUserId(user.getId()).stream()
                .filter(t -> t.getIsDuplicateOf() == null).toList();
    }

    private int statementImportsOf(User user) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM statement_imports WHERE user_id = ?", Integer.class, user.getId());
        return count == null ? 0 : count;
    }

    private List<ImportJob> jobsOf(User user) {
        return jobRepository.findByUserIdOrderByCreatedAtDesc(user.getId(),
                org.springframework.data.domain.PageRequest.of(0, 20));
    }

    private String heldIdOf(UUID jobId) {
        return heldStatementRepository.findByImportJobId(jobId).orElseThrow().getHeldId();
    }

    private List<Notification> notificationsOf(User user, NotificationType type) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .filter(n -> n.getType() == type).toList();
    }

    // ---------------------------------------------------------------------------------------
    // States no single build can produce
    // ---------------------------------------------------------------------------------------

    /** The state, and the notification, {@code ImportJobWorker.recordFailure} leaves an unclassified
     *  dead-letter in -- as {@code HeldImportReprocessOutcomeIT} puts it in place. */
    private void holdAsTheWorkerWould(UUID jobId) {
        transactionTemplate.executeWithoutResult(status -> {
            ImportJob job = jobRepository.findById(jobId).orElseThrow();
            job.markClaimed("worker", Instant.now());
            job.markClaimed("worker", Instant.now());
            job.recordFailure("IllegalStateException: no header row found", "IllegalStateException",
                    ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT, Instant.now());
            job.holdForReview("IllegalStateException", Instant.now());
            jobRepository.save(job);
            statementStatusNotifier.notifyHeld(job);
        });
    }

    /**
     * A statement this build reads cleanly, held for trust review with a review an operator can
     * act on: uploaded and staged by the real worker, then held as the worker holds a job whose
     * review record it could not write, and the review opened through the operator's own action.
     */
    private ImportJob cleanStatementHeldWithAReview(User owner, UUID admin) throws Exception {
        UUID jobId = upload(owner, CLEAN_WITH_BALANCE, "statement.csv");
        worker.drainOnce();
        ImportJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.COMPLETED);
        job.holdForTrustReview(job.getImportSessionId(), null, Instant.now());
        jobRepository.save(job);
        heldStatementService.openReviewForHoldWithoutRecord(admin, jobId);
        ImportJob held = jobRepository.findById(jobId).orElseThrow();
        assertThat(held.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_TRUST_REVIEW);
        assertThat(held.getHeldStatementId()).isNotNull();
        return held;
    }

    /**
     * A different build staged this user's sessions -- what a deploy between two uploads looks
     * like to {@code findLiveSessionByContentHash}. Both of its tests are satisfied: the stored
     * build id differs from any real one, and the session is older than the short window it falls
     * back to when the running build cannot be identified ({@code BuildVersionResolver}).
     */
    private void markSessionsStagedByAnOlderBuild(User owner) {
        int marked = jdbc.update("UPDATE import_sessions SET parser_version = '0ldbld0', "
                + "created_at = created_at - interval '1 hour' WHERE user_id = ?", owner.getId());
        assertThat(marked).isPositive();
    }
}
