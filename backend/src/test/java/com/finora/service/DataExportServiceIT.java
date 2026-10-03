package com.finora.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.AuditLog;
import com.finora.entity.ImportJob;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.StatementUpload;
import com.finora.imports.jobs.ImportJobService;
import com.finora.imports.storage.ContentAddress;
import com.finora.repository.AccountRepository;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link DataExportServiceTest} proves the export's decision logic against mocks; this proves,
 * against a real Postgres, the one thing only a real transaction manager can: {@link
 * DataExportService}'s own class doc explains that {@code writeZip} must resolve each statement's
 * bytes AFTER {@code buildBundle}'s transaction has already closed -- exactly the situation
 * {@code StreamingResponseBody} creates in production, where the write callback runs on a separate
 * thread once the controller method has already returned. {@code StatementImport.fileContent} is
 * {@code @Basic(fetch = FetchType.LAZY)}, so this test deliberately does NOT wrap itself in
 * {@code @Transactional} -- doing so would keep one Hibernate session open across both calls and
 * let a design that reads the lazy field at the wrong time pass anyway.
 *
 * <p>Object storage is configured (filesystem) so an upload can go through {@link
 * ImportJobService#accept} for real -- encrypted, addressed, and read back through the same path
 * production uses. The queue is off so no worker moves a job while a test is arranging its status.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-data-export-it",
        "app.import.queue.enabled=false"
})
class DataExportServiceIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private ImportJobService importJobService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private FeatureUsageService featureUsageService;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private com.finora.repository.SubscriptionRepository subscriptionRepository;
    @Autowired private com.finora.repository.PaymentRepository paymentRepository;
    @Autowired private com.finora.repository.SubscriptionOrderRepository subscriptionOrderRepository;
    @Autowired private com.finora.repository.ReferralRepository referralRepository;
    @Autowired private com.finora.repository.ReferralCodeRepository referralCodeRepository;
    @Autowired private com.finora.repository.ReferralChargeRepository referralChargeRepository;
    @Autowired private com.finora.repository.WalletLedgerRepository walletLedgerRepository;
    @Autowired private com.finora.notification.repository.NotificationRepository notificationRepository;
    @Autowired private com.finora.notification.repository.NotificationPreferenceRepository notificationPreferenceRepository;
    @Autowired private com.finora.timeline.TimelineEventRepository timelineEventRepository;
    @Autowired private com.finora.repository.TransactionRelationshipRepository transactionRelationshipRepository;
    @Autowired private com.finora.repository.StatementImportExcludedRowRepository statementImportExcludedRowRepository;
    @Autowired private com.finora.repository.CounterpartyCategoryObservationRepository counterpartyCategoryObservationRepository;
    @Autowired private com.finora.repository.StatementRefreshRunRepository statementRefreshRunRepository;
    @Autowired private DataExportService service;

    private UUID userId;
    private static final String PASSWORD = "correct horse battery staple";

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("export-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setFullName("Export Test User");
        userId = userRepository.save(user).getId();

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Export Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(1000));
        accountRepository.save(account);
    }

    /**
     * The regression this class exists to catch: reading {@code fileContent} from inside {@code
     * writeZip} using a {@link StatementImport} entity captured back in {@code buildBundle} (rather
     * than re-fetching it fresh via {@code StatementImportService.getFile}) would throw {@code
     * LazyInitializationException} here -- caught internally by {@code writeZip}'s own per-statement
     * try/catch, which would silently turn this test's real statement entry into a {@code
     * .MISSING.txt} placeholder instead of surfacing a loud test failure. So this test asserts the
     * REAL entry is present with the exact original bytes, not merely that nothing threw.
     */
    @Test
    void writeZip_readsLegacyFileContentBytes_afterBuildBundlesTransactionHasAlreadyClosed() throws Exception {
        byte[] originalBytes = "the quick brown fox jumps over the lazy dog".getBytes();
        Account account = accountRepository.findByUserId(userId).get(0);
        StatementImport statement = new StatementImport();
        statement.setUserId(userId);
        statement.setAccountId(account.getId());
        statement.setFileName("legacy-statement.csv");
        statement.setSourceFormat("CSV");
        statement.setFileContent(originalBytes);
        statement.setContentHash("export-it-hash-" + UUID.randomUUID());
        UUID statementId = statementImportRepository.save(statement).getId();

        // buildBundle runs its own @Transactional(readOnly = true) and returns -- its Hibernate
        // session is closed by the time this line completes, exactly like the controller's
        // transaction is closed by the time StreamingResponseBody's callback thread runs writeZip.
        DataExportService.ExportBundle bundle = service.buildBundle(userId, PASSWORD, null, null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeZip(userId, bundle, out);

        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), zis.readAllBytes());
            }
        }

        String expectedEntry = "statements/" + statementId + "-legacy-statement.csv";
        assertThat(entries).containsKey(expectedEntry);
        assertThat(entries.get(expectedEntry)).isEqualTo(originalBytes);
        assertThat(entries.keySet()).noneMatch(name -> name.contains(".MISSING.txt"));
    }

    /**
     * Regression test for a real bug found via manual verification, not written speculatively:
     * {@code buildBundle} is {@code @Transactional(readOnly = true)}; a plain {@code
     * auditService.record(...)} call immediately followed by throwing {@code ApiException} (a
     * {@code RuntimeException}) was silently rolled back along with the rest of that transaction --
     * the row was never visible in {@code audit_logs} despite {@code record()} having been called,
     * and only a mocked {@code AuditServiceTest} would ever have missed this, since a mock has no
     * transaction to roll back. See {@code AuditService.recordEvenOnRollback}'s own doc comment.
     */
    /**
     * feature_views.json against a real Postgres and the application's own ObjectMapper: the rows
     * are written through the same native upsert the Billing page uses, and another user's counter
     * on the same feature must not appear in this user's export.
     */
    @Test
    void writeZip_featureViews_exportsOnlyThisUsersCountersWithRealValues() throws Exception {
        Instant before = Instant.now().minusSeconds(5);
        featureUsageService.recordView(userId, "insights");
        featureUsageService.recordView(userId, "insights");

        User otherUser = new User();
        otherUser.setEmail("export-it-other-" + UUID.randomUUID() + "@example.com");
        otherUser.setPasswordHash(passwordEncoder.encode(PASSWORD));
        otherUser.setFullName("Other Export Test User");
        UUID otherUserId = userRepository.save(otherUser).getId();
        featureUsageService.recordView(otherUserId, "insights");

        DataExportService.ExportBundle bundle = service.buildBundle(userId, PASSWORD, null, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeZip(userId, bundle, out);

        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), zis.readAllBytes());
            }
        }

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonNode featureViews = mapper.readTree(entries.get("feature_views.json"));
        assertThat(featureViews).hasSize(1);
        assertThat(featureViews.get(0).get("feature").asText()).isEqualTo("INSIGHTS");
        assertThat(featureViews.get(0).get("viewCount").asInt()).isEqualTo(2);
        Instant lastViewedAt = mapper.treeToValue(featureViews.get(0).get("lastViewedAt"), Instant.class);
        assertThat(lastViewedAt).isAfter(before).isBefore(Instant.now().plusSeconds(5));

        List<JsonNode> manifestEntries = new ArrayList<>();
        mapper.readTree(entries.get("manifest.json")).get("included").forEach(n -> {
            if (n.get("name").asText().equals("feature_views.json")) manifestEntries.add(n);
        });
        assertThat(manifestEntries).hasSize(1);
        assertThat(manifestEntries.get(0).get("rowCount").asInt()).isEqualTo(1);
    }

    /**
     * The tables added to close the rest of the F-03 gap, against a real Postgres and the
     * application's own ObjectMapper: real rows written the same way AccountPurgeSweepServiceIT
     * writes them, each read back out of the ZIP with its values, and the identifiers that must
     * never leave (the other person in a referral, a referred person's charge, notification
     * delivery bookkeeping) absent from every file in the archive.
     */
    @Test
    void writeZip_exportsTheRemainingPurgedTablesFromARealDatabase() throws Exception {
        subscriptionService.provisionFreeSubscription(userId);
        com.finora.entity.Subscription subscription = subscriptionRepository.findActiveOrTrial(userId).orElseThrow();

        com.finora.entity.Payment payment = new com.finora.entity.Payment();
        payment.setUserId(userId);
        payment.setSubscriptionId(subscription.getId());
        payment.setPlanId(subscription.getPlanId());
        payment.setAmount(BigDecimal.valueOf(499));
        payment.setCurrency("INR");
        payment.setStatus(com.finora.entity.Payment.STATUS_SUCCESS);
        payment.setProviderTransactionId("pay_export_it");
        paymentRepository.save(payment);

        com.finora.entity.SubscriptionOrder order = new com.finora.entity.SubscriptionOrder();
        order.setUserId(userId);
        order.setPlanId(subscription.getPlanId());
        order.setBillingCycle("MONTHLY");
        order.setStatus(com.finora.entity.SubscriptionOrder.STATUS_COMPLETED);
        order.setAmount(BigDecimal.valueOf(399));
        order.setRazorpaySubscriptionId("sub_export_it_secret");
        subscriptionOrderRepository.save(order);

        User otherUser = new User();
        otherUser.setEmail("export-it-referral-" + UUID.randomUUID() + "@example.com");
        otherUser.setPasswordHash(passwordEncoder.encode(PASSWORD));
        otherUser.setFullName("Referral Other User");
        UUID otherUserId = userRepository.save(otherUser).getId();

        com.finora.entity.ReferralCode code = new com.finora.entity.ReferralCode();
        code.setUserId(userId);
        code.setCode("EXP" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        referralCodeRepository.save(code);
        com.finora.entity.Referral invitedByOther = new com.finora.entity.Referral();
        invitedByOther.setReferrerUserId(otherUserId);
        invitedByOther.setReferredUserId(userId);
        invitedByOther.setStatus(com.finora.entity.Referral.STATUS_REGISTERED);
        invitedByOther.setReward(new BigDecimal("61.37"));
        referralRepository.save(invitedByOther);
        com.finora.entity.Referral invitedOther = new com.finora.entity.Referral();
        invitedOther.setReferrerUserId(userId);
        invitedOther.setReferredUserId(otherUserId);
        invitedOther.setStatus(com.finora.entity.Referral.STATUS_REGISTERED);
        referralRepository.save(invitedOther);
        com.finora.entity.ReferralCharge charge = new com.finora.entity.ReferralCharge();
        charge.setReferrerUserId(userId);
        charge.setReferralId(invitedOther.getId());
        charge.setProvider(com.finora.entity.ReferralCharge.PROVIDER_RAZORPAY);
        String otherPersonsChargeRef = "pay_other_person_" + UUID.randomUUID();
        charge.setChargeRef(otherPersonsChargeRef);
        charge.setCounted(true);
        referralChargeRepository.save(charge);

        com.finora.entity.WalletLedgerEntry walletEntry = new com.finora.entity.WalletLedgerEntry();
        walletEntry.setUserId(userId);
        walletEntry.setAmount(BigDecimal.valueOf(100));
        walletEntry.setReason(com.finora.entity.WalletLedgerEntry.REASON_REFERRAL_REWARD);
        walletLedgerRepository.save(walletEntry);

        String notificationKey = "export-it-key-" + UUID.randomUUID();
        notificationRepository.save(com.finora.notification.domain.Notification.create(userId,
                com.finora.notification.domain.NotificationType.PASSWORD_CHANGED,
                com.finora.notification.domain.NotificationCategory.SECURITY,
                com.finora.notification.domain.NotificationChannel.EMAIL,
                com.finora.notification.domain.NotificationPriority.NORMAL,
                notificationKey, "Your password was changed", "The password on your account was just changed.",
                Instant.now()));
        notificationPreferenceRepository.save(com.finora.notification.domain.NotificationPreference.of(userId,
                com.finora.notification.domain.NotificationCategory.MARKETING,
                com.finora.notification.domain.NotificationChannel.EMAIL, false));

        com.finora.timeline.TimelineEvent event = new com.finora.timeline.TimelineEvent();
        event.setUserId(userId);
        event.setEventType("GOAL_COMPLETED");
        event.setBucket("MILESTONE");
        event.setImportance("HIGH");
        event.setPermanent(true);
        event.setTitle("Completed Emergency Fund");
        event.setOccurredAt(Instant.now());
        event.setCreatedAt(Instant.now());
        timelineEventRepository.save(event);

        UUID fromTransactionId = UUID.randomUUID();
        UUID toTransactionId = UUID.randomUUID();
        com.finora.entity.TransactionRelationship edge = new com.finora.entity.TransactionRelationship();
        edge.setUserId(userId);
        edge.setFromTransactionId(fromTransactionId);
        edge.setToTransactionId(toTransactionId);
        edge.setRelationshipType(com.finora.entity.TransactionRelationship.RelationshipType.DUPLICATE);
        edge.setDetectionMethod(com.finora.entity.TransactionRelationship.DetectionMethod.RULE_ENGINE);
        transactionRelationshipRepository.save(edge);

        Account account = accountRepository.findByUserId(userId).get(0);
        StatementImport statement = new StatementImport();
        statement.setUserId(userId);
        statement.setAccountId(account.getId());
        statement.setFileName("excluded-rows.csv");
        statement.setSourceFormat("CSV");
        statement.setFileContent("x".getBytes());
        statement.setContentHash("export-it-excluded-" + UUID.randomUUID());
        UUID statementId = statementImportRepository.save(statement).getId();
        statementImportExcludedRowRepository.save(new com.finora.entity.StatementImportExcludedRow(statementId, userId, 3,
                java.time.LocalDate.of(2026, 9, 1), "OPENING BALANCE", new BigDecimal("12.50"), "CREDIT", false));

        com.finora.entity.StatementRefreshRun refreshRun = new com.finora.entity.StatementRefreshRun(
                statementId, userId, "parser-export-it", com.finora.entity.StatementRefreshRun.Status.APPLIED);
        refreshRun.setCounts(0, 0, 1, 0);
        refreshRun.setDetail(Map.of("removed", List.of(Map.of("transactionId", UUID.randomUUID().toString(),
                "date", "2026-09-02", "description", "EXPORT IT REMOVED ROW", "amount", "75.00", "type", "EXPENSE",
                "userEdited", false))));
        statementRefreshRunRepository.save(refreshRun);

        com.finora.entity.CounterpartyCategoryObservation vote = new com.finora.entity.CounterpartyCategoryObservation();
        vote.setCounterpartyKey("vpa:export-it@okbank");
        vote.setDirection(com.finora.entity.Transaction.Type.EXPENSE);
        vote.setCategory("Dining");
        vote.setUserId(userId);
        vote.setCounterpartyTypeAtVote(com.finora.util.CounterpartyType.BUSINESS);
        counterpartyCategoryObservationRepository.save(vote);

        DataExportService.ExportBundle bundle = service.buildBundle(userId, PASSWORD, null, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeZip(userId, bundle, out);
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), zis.readAllBytes());
            }
        }
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        JsonNode payments = mapper.readTree(entries.get("payments.json"));
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).get("providerTransactionId").asText()).isEqualTo("pay_export_it");
        assertThat(payments.get(0).get("planCode").isNull()).isFalse();
        JsonNode orders = mapper.readTree(entries.get("subscription_orders.json"));
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).get("billingCycle").asText()).isEqualTo("MONTHLY");

        JsonNode referrals = mapper.readTree(entries.get("referrals.json"));
        assertThat(referrals).hasSize(2);
        assertThat(referrals.findValuesAsText("role")).containsExactlyInAnyOrder("REFERRER", "REFERRED");
        for (JsonNode referral : referrals) {
            if (referral.get("role").asText().equals("REFERRED")) {
                assertThat(referral.get("reward").isNull()).as("the inviter's reward").isTrue();
            }
        }
        assertThat(mapper.readTree(entries.get("referral_code.json")).get(0).get("code").asText()).isEqualTo(code.getCode());
        JsonNode wallet = mapper.readTree(entries.get("wallet.json"));
        assertThat(wallet).hasSize(1);
        assertThat(wallet.get(0).get("amount").decimalValue()).isEqualByComparingTo("100");

        JsonNode notifications = mapper.readTree(entries.get("notifications.json"));
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).get("title").asText()).isEqualTo("Your password was changed");
        JsonNode preferences = mapper.readTree(entries.get("notification_preferences.json"));
        assertThat(preferences).hasSize(1);
        assertThat(preferences.get(0).get("enabled").asBoolean()).isFalse();

        assertThat(mapper.readTree(entries.get("timeline.json")).get(0).get("title").asText())
                .isEqualTo("Completed Emergency Fund");
        JsonNode links = mapper.readTree(entries.get("transaction_links.json"));
        assertThat(links).hasSize(1);
        assertThat(links.get(0).get("fromTransactionId").asText()).isEqualTo(fromTransactionId.toString());
        assertThat(links.get(0).get("relationshipType").asText()).isEqualTo("DUPLICATE");
        JsonNode excludedRows = mapper.readTree(entries.get("statement_excluded_rows.json"));
        assertThat(excludedRows).hasSize(1);
        assertThat(excludedRows.get(0).get("statementImportId").asText()).isEqualTo(statementId.toString());
        assertThat(excludedRows.get(0).get("amount").decimalValue()).isEqualByComparingTo("12.50");
        JsonNode refreshRuns = mapper.readTree(entries.get("statement_refresh_runs.json"));
        assertThat(refreshRuns).hasSize(1);
        assertThat(refreshRuns.get(0).get("fileName").asText()).isEqualTo("excluded-rows.csv");
        assertThat(refreshRuns.get(0).get("accountName").asText()).isEqualTo("Export Test Savings");
        assertThat(refreshRuns.get(0).get("removed").get(0).get("description").asText()).isEqualTo("EXPORT IT REMOVED ROW");
        JsonNode votes = mapper.readTree(entries.get("merchant_category_votes.json"));
        assertThat(votes).hasSize(1);
        assertThat(votes.get(0).get("category").asText()).isEqualTo("Dining");

        // Nothing that identifies someone else, or is delivery/correlation bookkeeping, leaves --
        // checked across every file in the archive, not just the one it would naturally sit in.
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (!e.getKey().endsWith(".json")) continue;
            String json = new String(e.getValue(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(json).as(e.getKey()).doesNotContain(otherUserId.toString())
                    .doesNotContain(otherPersonsChargeRef)
                    .doesNotContain(notificationKey)
                    .doesNotContain("sub_export_it_secret");
        }
    }

    @Test
    void buildBundle_wrongPassword_stillPersistsTheAuditRow_despiteTheTransactionRollingBack() {
        assertThrows(ApiException.class, () -> service.buildBundle(userId, "definitely-the-wrong-password", null, null));

        List<AuditLog> logs = auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId);
        assertThat(logs).anySatisfy(log -> assertThat(log.getAction()).isEqualTo("INVALID_CURRENT_PASSWORD"));
    }

    /**
     * An upload that never became a statement -- held for review, still queued, or failed -- has
     * no {@code statement_imports} row, so the {@code statements/} loop never sees it. The file is
     * still the user's and still held by Finora (in object storage, under the job's own address),
     * so it belongs in the export, read back through the same decrypting path the worker uses.
     */
    @Test
    void writeZip_includesUploadsThatNeverBecameAStatement_heldQueuedAndFailed() throws Exception {
        byte[] heldBytes = "%PDF-1.4 held upload".getBytes(StandardCharsets.UTF_8);
        byte[] queuedBytes = "date,narration,amount\n2026-09-01,queued upload,10\n".getBytes(StandardCharsets.UTF_8);
        byte[] failedBytes = "%PDF-1.4 failed upload".getBytes(StandardCharsets.UTF_8);

        ImportJob held = upload("hdfc-june.pdf", heldBytes, StatementUpload.Format.PDF);
        held.markClaimed("worker", Instant.now());
        held.recordFailure("IllegalStateException: no header row", "IllegalStateException",
                ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT, Instant.now());
        held.holdForReview("IllegalStateException", Instant.now());
        importJobRepository.save(held);

        ImportJob queued = upload("sbi-july.csv", queuedBytes, StatementUpload.Format.CSV);

        ImportJob failed = upload("icici/august?.pdf", failedBytes, StatementUpload.Format.PDF);
        failed.markClaimed("worker", Instant.now());
        failed.recordFailure("locked PDF", ErrorCode.RetryPolicy.FAIL_FAST, Instant.now());
        importJobRepository.save(failed);

        assertThat(importJobRepository.findById(held.getId()).orElseThrow().getStatus())
                .isEqualTo(ImportJob.Status.HELD_FOR_REVIEW);
        assertThat(importJobRepository.findById(queued.getId()).orElseThrow().getStatus())
                .isEqualTo(ImportJob.Status.QUEUED);
        assertThat(importJobRepository.findById(failed.getId()).orElseThrow().getStatus())
                .isEqualTo(ImportJob.Status.FAILED);

        Map<String, byte[]> entries = export();

        assertThat(entries.get("imports/" + held.getId() + "-hdfc-june.pdf")).isEqualTo(heldBytes);
        assertThat(entries.get("imports/" + queued.getId() + "-sbi-july.csv")).isEqualTo(queuedBytes);
        // The filename is sanitized exactly like statements/ entries: no path separator survives.
        assertThat(entries.get("imports/" + failed.getId() + "-icici_august_.pdf")).isEqualTo(failedBytes);
        assertThat(entries.keySet()).noneMatch(name -> name.contains(".MISSING.txt"));

        String manifest = new String(entries.get("manifest.json"), StandardCharsets.UTF_8);
        assertThat(manifest).contains("\"name\":\"imports/\"");
    }

    /**
     * The same document already exported under {@code statements/} is not exported a second time
     * under {@code imports/}. Matched by {@code content_hash} -- the SHA-256 of the original bytes,
     * which both tables record -- not by {@code object_key}: the job's object and the confirmed
     * statement's object are written separately (the statement's gzipped, both encrypted under a
     * fresh random IV), so their keys never agree even for byte-identical documents.
     */
    @Test
    void writeZip_doesNotRepeatUnderImportsADocumentAlreadyExportedUnderStatements() throws Exception {
        byte[] bytes = "%PDF-1.4 imported fine".getBytes(StandardCharsets.UTF_8);
        ImportJob job = upload("kotak-may.pdf", bytes, StatementUpload.Format.PDF);

        Account account = accountRepository.findByUserId(userId).get(0);
        StatementImport statement = new StatementImport();
        statement.setUserId(userId);
        statement.setAccountId(account.getId());
        statement.setFileName("kotak-may.pdf");
        statement.setSourceFormat("PDF");
        statement.setFileContent(bytes);
        // Derived the way a confirmed statement records it (ImportService.persistSection), not
        // copied from the job -- so this also proves the two tables agree on what the hash is.
        statement.setContentHash(ContentAddress.hashOf(bytes));
        assertThat(statement.getContentHash()).isEqualTo(job.getContentHash());
        UUID statementId = statementImportRepository.save(statement).getId();

        Map<String, byte[]> entries = export();

        assertThat(entries.get("statements/" + statementId + "-kotak-may.pdf")).isEqualTo(bytes);
        assertThat(entries.keySet()).noneMatch(name -> name.startsWith("imports/"));
    }

    /**
     * The same file uploaded twice (two failed attempts, say) is one document: one entry, from the
     * most recent job.
     */
    @Test
    void writeZip_exportsARepeatedlyUploadedDocumentOnce() throws Exception {
        byte[] bytes = "%PDF-1.4 uploaded twice".getBytes(StandardCharsets.UTF_8);
        ImportJob first = upload("axis-april.pdf", bytes, StatementUpload.Format.PDF);
        first.markClaimed("worker", Instant.now());
        first.recordFailure("locked PDF", ErrorCode.RetryPolicy.FAIL_FAST, Instant.now());
        importJobRepository.save(first);
        // A terminal first job is what lets the second upload become a job of its own -- a live
        // one with the same content hash would be returned instead (BH-019).
        ImportJob second = upload("axis-april (1).pdf", bytes, StatementUpload.Format.PDF);
        assertThat(second.getId()).isNotEqualTo(first.getId());

        Map<String, byte[]> entries = export();

        assertThat(entries.keySet().stream().filter(name -> name.startsWith("imports/")))
                .containsExactly("imports/" + second.getId() + "-axis-april _1_.pdf");
    }

    private ImportJob upload(String fileName, byte[] bytes, StatementUpload.Format format) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", fileName, format.contentType(), bytes);
        return importJobRepository.findById(importJobService.accept(userId, file, format).getId()).orElseThrow();
    }

    private Map<String, byte[]> export() throws Exception {
        DataExportService.ExportBundle bundle = service.buildBundle(userId, PASSWORD, null, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeZip(userId, bundle, out);
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), zis.readAllBytes());
            }
        }
        return entries;
    }
}
