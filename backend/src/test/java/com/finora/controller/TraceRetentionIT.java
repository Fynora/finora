package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.imports.blanked.TraceRetentionService;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The private trace repository's nightly retention job asks this endpoint which held-item
 * references no longer exist, then deletes the traces made from them. So the endpoint must answer
 * only for the dedicated key, look nothing up for anyone else, and return a subset of what it was
 * asked -- never a user, an email or a file name.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "app.trace-retention.key=" + TraceRetentionIT.KEY)
class TraceRetentionIT extends AbstractIntegrationTest {

    static final String KEY = "test-retention-key-for-it-only";
    private static final String PATH = "/api/v1/internal/trace-retention/gone";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private HeldStatementRepository heldStatementRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @MockitoSpyBean private TraceRetentionService service;

    private User createUser() {
        User user = new User();
        user.setEmail("trace-retention-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Trace Retention IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private ImportJob job(User owner) {
        String hash = UUID.randomUUID().toString().replace("-", "");
        return importJobRepository.save(new ImportJob(owner.getId(), "statement.pdf", hash, "objects/" + hash, "PDF"));
    }

    private HeldStatement hold(String heldId, ImportJob job) {
        return heldStatementRepository.save(new HeldStatement(heldId, job.getId(), job.getUserId(),
                job.getObjectKey(), "Printed and parsed transaction count disagree (ROW_GROUPING)"));
    }

    /** A held id no other test or seed uses: the year 2099 and a random sequence. */
    private static String freshHeldId() {
        return "HLD-2099-" + String.format("%06d", (int) (Math.random() * 900_000) + 100_000);
    }

    private ResponseEntity<String> ask(String key, List<String> references) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) headers.set("X-Trace-Retention-Key", key);
        return restTemplate.exchange(PATH, HttpMethod.POST,
                new HttpEntity<>(Map.of("references", references), headers), String.class);
    }

    private List<String> gone(ResponseEntity<String> response) throws Exception {
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.fieldNames()).toIterable().containsExactly("gone");
        List<String> gone = new ArrayList<>();
        body.get("gone").forEach(n -> gone.add(n.asText()));
        return gone;
    }

    @Test
    void aMissingKeyIsUnauthorized() {
        String asked = UUID.randomUUID().toString();
        clearInvocations(service);

        ResponseEntity<String> response = ask(null, List.of(asked));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody() == null ? "" : response.getBody()).doesNotContain(asked);
        verify(service, never()).gone(any());
    }

    @Test
    void aWrongKeyIsUnauthorized() {
        clearInvocations(service);

        ResponseEntity<String> response = ask(KEY + "x", List.of(UUID.randomUUID().toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(service, never()).gone(any());
    }

    @Test
    void moreThan500ReferencesAreRefused() {
        clearInvocations(service);
        List<String> refs = IntStream.range(0, 501).mapToObj(i -> UUID.randomUUID().toString()).toList();

        ResponseEntity<String> response = ask(KEY, refs);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).gone(any());
    }

    @Test
    void exactly500ReferencesAreAnswered() throws Exception {
        List<String> refs = IntStream.range(0, 500).mapToObj(i -> UUID.randomUUID().toString()).toList();

        ResponseEntity<String> response = ask(KEY, refs);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(gone(response)).containsExactlyInAnyOrderElementsOf(refs);
    }

    /** No Authorization header at all: the key alone is the authentication, so the user-session
     *  filters must let this through to the controller (a 401 from them would also read as "wrong
     *  key", which is why the right key with no header has to come back 200). */
    @Test
    void onlyReferencesWhoseJobIsGoneComeBack(CapturedOutput output) throws Exception {
        User owner = createUser();
        ImportJob existing = job(owner);
        ImportJob deleted = job(owner);
        importJobRepository.delete(deleted);
        String unknown = UUID.randomUUID().toString();
        String existingHeldId = freshHeldId();
        hold(existingHeldId, job(owner));
        String goneHeldId = freshHeldId();
        HeldStatement goneHold = hold(goneHeldId, job(owner));
        heldStatementRepository.delete(goneHold);

        ResponseEntity<String> response = ask(KEY, List.of(existing.getId().toString(),
                deleted.getId().toString(), unknown, existingHeldId, goneHeldId, "not-a-reference"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // A malformed reference is not "gone": deleting a trace on a typo would lose a test for nothing.
        assertThat(gone(response)).containsExactlyInAnyOrder(deleted.getId().toString(), unknown, goneHeldId);
        assertThat(output.getAll()).contains("trace retention: 6 asked, 3 gone");
        assertThat(output.getAll()).doesNotContain(owner.getEmail()).doesNotContain(goneHeldId + " ");
    }

    /** The case the endpoint exists for: account purge deletes the customer's import jobs
     *  (AccountPurgeSweepService), and the hold row goes with its job (ON DELETE CASCADE, V144).
     *  Both forms of reference to that customer's statement then come back. */
    @Test
    void afterTheCustomersJobsArePurged_bothFormsOfReferenceAreGone() throws Exception {
        User owner = createUser();
        ImportJob job = job(owner);
        String heldId = freshHeldId();
        hold(heldId, job);
        User other = createUser();
        ImportJob kept = job(other);
        String keptHeldId = freshHeldId();
        hold(keptHeldId, kept);
        List<String> asked = List.of(job.getId().toString(), heldId, kept.getId().toString(), keptHeldId);

        assertThat(gone(ask(KEY, asked))).isEmpty();

        transactionTemplate.executeWithoutResult(s -> importJobRepository.deleteByUserId(owner.getId()));

        assertThat(gone(ask(KEY, asked))).containsExactlyInAnyOrder(job.getId().toString(), heldId);
    }

    /** Infrastructure, not product API: no client is generated from it, so it stays out of the
     *  committed OpenAPI spec that openapi-contract-check diffs against. */
    @Test
    void theEndpointIsNotInThePublishedApiSpec() {
        ResponseEntity<String> spec = restTemplate.getForEntity("/v3/api-docs", String.class);

        assertThat(spec.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(spec.getBody()).contains("Finora API").doesNotContain("trace-retention")
                .doesNotContain("TraceRetention");
    }

    @Test
    void anEmptyQuestionGetsAnEmptyAnswer() throws Exception {
        assertThat(gone(ask(KEY, List.of()))).isEmpty();
    }
}
