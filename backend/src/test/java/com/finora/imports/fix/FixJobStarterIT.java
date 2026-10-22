package com.finora.imports.fix;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.AuditLog;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.exception.ErrorCode;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.storage.ContentAddress;
import com.finora.imports.storage.StatementStorage;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real hold, through the real starter, its executor, the blanked-copy service and the GitHub
 * channel, against a local server standing in for api.github.com. The hold's after-commit hook
 * (the worker's and the trust queue's) is checked by their unit tests; here the starter is called
 * as that hook calls it. Statements are the synthetic fixtures with planted customer data.
 */
@TestPropertySource(properties = {
        "app.claude-fix.enabled=true",
        "app.claude-fix.github-token=test-token",
        // The follow-up is FixJobFollowUpTest's: kept from polling the stand-in during this class.
        "app.claude-fix.follow-up-initial-delay-ms=86400000",
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-it-statement-storage"
})
@ExtendWith(OutputCaptureExtension.class)
class FixJobStarterIT extends AbstractIntegrationTest {

    private static final HttpServer GITHUB;
    /** The status the stand-in answers PUTs with. */
    private static final AtomicInteger PUT_STATUS = new AtomicInteger(201);
    private static final List<String> PUT_BODIES = new CopyOnWriteArrayList<>();
    private static final List<String> PUT_PATHS = new CopyOnWriteArrayList<>();

    static {
        try {
            GITHUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        GITHUB.createContext("/", exchange -> {
            PUT_PATHS.add(exchange.getRequestURI().getRawPath());
            PUT_BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = PUT_STATUS.get();
            byte[] body = (status == 201 ? "{\"commit\":{\"sha\":\"sha-" + PUT_PATHS.size() + "\"}}"
                    : "{\"message\":\"Server Error\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        GITHUB.start();
    }

    @DynamicPropertySource
    static void github(DynamicPropertyRegistry registry) {
        registry.add("app.claude-fix.api-base", () -> "http://127.0.0.1:" + GITHUB.getAddress().getPort());
    }

    @Autowired private FixJobStarter starter;
    @Autowired private ClaudeFixJobRepository fixJobs;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private HeldStatementRepository heldStatementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private StatementStorage storage;

    @BeforeEach
    void reset() {
        PUT_STATUS.set(201);
        PUT_BODIES.clear();
        PUT_PATHS.clear();
    }

    private User owner() {
        User user = new User();
        user.setEmail("claude-fix-starter-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Claude Fix Starter IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private ImportJob importHold(byte[] content) {
        ContentAddress address = storage.store(content);
        ImportJob job = new ImportJob(owner().getId(), "Asha Verma statement.pdf", address.hash(), address.key(), "PDF");
        job.markClaimed("worker", Instant.now());
        job.markClaimed("worker", Instant.now());
        job.recordFailure("IllegalStateException: no header row", "IllegalStateException",
                ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT, Instant.now());
        job.holdForReview("IllegalStateException", Instant.now());
        return importJobRepository.save(job);
    }

    private ImportJob trustHold(String heldId, byte[] content) {
        User owner = owner();
        ContentAddress address = storage.store(content);
        ImportJob job = new ImportJob(owner.getId(), "Asha Verma statement.pdf", address.hash(), address.key(), "PDF");
        job.markClaimed("worker", Instant.now());
        UUID sessionId = UUID.randomUUID();
        job.holdForTrustReview(sessionId, null, Instant.now());
        importJobRepository.save(job);
        HeldStatement held = new HeldStatement(heldId, job.getId(), owner.getId(), job.getObjectKey(),
                "Printed and parsed transaction count disagree (ROW_GROUPING)");
        held.recordBank("Synthetic Bank");
        held = heldStatementRepository.save(held);
        job.holdForTrustReview(sessionId, held.getId(), Instant.now());
        return importJobRepository.save(job);
    }

    private static byte[] matching() {
        try {
            return PdfFixtureBuilder.buildSampleWithPlantedCustomerData();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] differing() {
        try {
            return PdfFixtureBuilder.buildDirectionOnlyAsAWordSample();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The starter runs on its own thread, and reserves its row ("could not start") before it
     *  makes the copy; waits for the decision that replaces the reservation. */
    private ClaudeFixJob rowFor(UUID jobId) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            Optional<ClaudeFixJob> row = fixJobs.findById(jobId);
            if (row.isPresent() && row.get().getReason() != ClaudeFixJob.Reason.ERROR) return row.get();
            Thread.sleep(100);
        }
        throw new AssertionError("no decided fix job row for " + jobId + " after 30 s");
    }

    /** As {@link #rowFor}, for a start whose send fails: the reservation's ERROR is its decision.
     *  Waits for the failed call, then for the starter to finish with it. */
    private ClaudeFixJob rowAfterAFailedSend(UUID jobId) throws InterruptedException {
        for (int i = 0; i < 300 && PUT_PATHS.isEmpty(); i++) Thread.sleep(100);
        Thread.sleep(1000);
        return fixJobs.findById(jobId).orElseThrow();
    }

    private static String decoded(String putBody) {
        String content = putBody.replaceAll("(?s).*\"content\":\"([^\"]*)\".*", "$1");
        return new String(Base64.getDecoder().decode(content), StandardCharsets.UTF_8);
    }

    @Test
    void aMatchingImportHoldIsSentOnceAsAttemptOne(CapturedOutput output) throws Exception {
        ImportJob job = importHold(matching());

        starter.onHeld(job.getId());
        ClaudeFixJob row = rowFor(job.getId());

        assertThat(row.getState()).isEqualTo(ClaudeFixJob.State.SENT);
        assertThat(row.getAttempt()).isEqualTo(1);
        assertThat(row.getSentCommitSha()).isEqualTo("sha-2");
        assertThat(PUT_PATHS).containsExactly(
                "/repos/Fynora/finora-statement-traces/contents/incoming/" + job.getId() + "-a1.trace",
                "/repos/Fynora/finora-statement-traces/contents/incoming/" + job.getId() + "-a1.json");
        String sent = decoded(PUT_BODIES.get(0)) + decoded(PUT_BODIES.get(1)) + String.join("", PUT_BODIES);
        assertThat(sent).doesNotContain(PdfFixtureBuilder.PLANTED_NAME, PdfFixtureBuilder.PLANTED_PHONE,
                PdfFixtureBuilder.PLANTED_EMAIL, PdfFixtureBuilder.PLANTED_ACCOUNT_NUMBER,
                PdfFixtureBuilder.PLANTED_IFSC, "Asha Verma", job.getUserId().toString());
        assertThat(decoded(PUT_BODIES.get(1))).isEqualTo("{\"reference\":\"" + job.getId()
                + "\",\"attempt\":1,\"bank\":null,\"reasonCodes\":[\"IllegalStateException\"],\"findingKinds\":[]}");
        assertThat(output.getAll()).doesNotContain("Asha Verma", PdfFixtureBuilder.PLANTED_NAME);

        List<AuditLog> audit = auditLogRepository.findByEntityIdOrderByCreatedAtAsc(job.getId());
        assertThat(audit).extracting(AuditLog::getAction)
                .containsExactly("HELD_IMPORT_BLANKED_COPY", "HELD_IMPORT_BLANKED_COPY_RESULT");
        assertThat(audit).allSatisfy(entry -> {
            assertThat(entry.getUserId()).isNull();
            assertThat(entry.getMetadata()).containsEntry("actor", "claude-fix").doesNotContainKey("actorId");
        });

        starter.onHeld(job.getId());
        Thread.sleep(500);
        assertThat(PUT_PATHS).hasSize(2);
    }

    @Test
    void aDifferingHoldIsNotStarted() throws Exception {
        ImportJob job = importHold(differing());

        starter.onHeld(job.getId());
        ClaudeFixJob row = rowFor(job.getId());

        assertThat(row.getState()).isEqualTo(ClaudeFixJob.State.NOT_STARTED);
        assertThat(row.getReason()).isEqualTo(ClaudeFixJob.Reason.DIFFERS);
        assertThat(PUT_PATHS).isEmpty();
    }

    @Test
    void aMatchingTrustHoldIsSentWithItsBank() throws Exception {
        ImportJob job = trustHold("HELD-CF" + (System.nanoTime() % 100000), matching());

        starter.onHeld(job.getId());
        ClaudeFixJob row = rowFor(job.getId());

        assertThat(row.getState()).isEqualTo(ClaudeFixJob.State.SENT);
        assertThat(decoded(PUT_BODIES.get(1))).contains("\"reference\":\"" + job.getId() + "\"")
                .contains("\"bank\":\"Synthetic Bank\"");
    }

    @Test
    void aFailedSendLeavesTheHoldAlone() throws Exception {
        PUT_STATUS.set(500);
        ImportJob job = importHold(matching());

        starter.onHeld(job.getId());
        ClaudeFixJob row = rowAfterAFailedSend(job.getId());

        assertThat(row.getState()).isEqualTo(ClaudeFixJob.State.NOT_STARTED);
        assertThat(row.getReason()).isEqualTo(ClaudeFixJob.Reason.ERROR);
        ImportJob after = importJobRepository.findById(job.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_REVIEW);
        assertThat(after.getFailureCode()).isEqualTo("IllegalStateException");
    }
}
