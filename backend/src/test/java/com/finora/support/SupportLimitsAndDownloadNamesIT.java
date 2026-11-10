package com.finora.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
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
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Support break-test fixes (2026-11-10), through the real server -- real Tomcat matters here, because
 * the dropped download header was Tomcat's doing, not Spring's:
 * <ul>
 *   <li>tickets and feedback sent at once no longer get past the daily ceilings, and the
 *       "we received it" email stays at 3 a day (10 tickets at once used to queue 8);</li>
 *   <li>a description over 10,000 characters and feedback over 5,000 are refused (1 MB and 2 MB
 *       were stored), and a description too big to read at all is refused in support's words;</li>
 *   <li>an attachment whose name is not plain ASCII downloads under that name (the header used to
 *       vanish), while an ASCII name keeps exactly the header it always had.</li>
 * </ul>
 */
class SupportLimitsAndDownloadNamesIT extends AbstractIntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createCustomer(boolean emailVerified) {
        User user = new User();
        user.setEmail("support-fixes-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Support Fixes IT");
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        user.setEmailVerified(emailVerified);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user, MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(contentType);
        return headers;
    }

    private ResponseEntity<String> postTicket(HttpHeaders headers, String subject, String description, String fileName) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("category", "OTHER");
        body.add("subject", subject);
        body.add("description", description);
        if (fileName != null) {
            body.add("file", new ByteArrayResource(PNG) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            });
        }
        return restTemplate.exchange("/api/v1/support/tickets", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> postFeedback(HttpHeaders headers, String message) throws Exception {
        String payload = mapper.writeValueAsString(java.util.Map.of("type", "GENERAL", "context", "OTHER", "message", message));
        return restTemplate.exchange("/api/v1/feedback", HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);
    }

    /** Runs {@code calls} requests released together and returns their statuses. */
    private List<HttpStatus> burst(int calls, Supplier<ResponseEntity<String>> request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<CompletableFuture<HttpStatus>> futures = new ArrayList<>();
            for (int i = 0; i < calls; i++) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return (HttpStatus) request.get().getStatusCode();
                }, pool));
            }
            start.countDown();
            List<HttpStatus> statuses = new ArrayList<>();
            for (CompletableFuture<HttpStatus> f : futures) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private long count(String sql, UUID userId) {
        return jdbc.queryForObject(sql, Long.class, userId);
    }

    @Test
    void ticketsSentAtOnce_stayWithinTheDailyCeiling_andTheAcknowledgementCap() throws Exception {
        for (int round = 0; round < 3; round++) {
            User customer = createCustomer(true);
            HttpHeaders headers = bearerFor(customer, MediaType.MULTIPART_FORM_DATA);

            List<HttpStatus> statuses = burst(15, () -> postTicket(headers, "Help", "It broke", null));

            assertThat(statuses).as("round " + round).filteredOn(s -> s == HttpStatus.OK).hasSize(10);
            assertThat(statuses).filteredOn(s -> s == HttpStatus.TOO_MANY_REQUESTS).hasSize(5);
            assertThat(count("SELECT count(*) FROM support_tickets WHERE user_id = ?", customer.getId())).isEqualTo(10);
            assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'SUPPORT_TICKET_RECEIVED'",
                    customer.getId())).as("acknowledgements, round " + round).isEqualTo(SupportTicketService.MAX_ACKNOWLEDGEMENTS_PER_DAY);
        }
    }

    @Test
    void feedbackSentAtOnce_staysWithinTheDailyCeiling() throws Exception {
        User customer = createCustomer(false);
        HttpHeaders headers = bearerFor(customer, MediaType.APPLICATION_JSON);

        List<HttpStatus> statuses = burst(30, () -> {
            try {
                return postFeedback(headers, "Feedback");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(statuses).filteredOn(s -> s == HttpStatus.OK).hasSize(20);
        assertThat(count("SELECT count(*) FROM feedback_entries WHERE user_id = ?", customer.getId())).isEqualTo(20);
    }

    @Test
    void overlongTextIsRefused_andTheLimitsThemselvesAreAccepted() throws Exception {
        User customer = createCustomer(false);
        HttpHeaders multipart = bearerFor(customer, MediaType.MULTIPART_FORM_DATA);
        HttpHeaders json = bearerFor(customer, MediaType.APPLICATION_JSON);

        assertThat(postTicket(multipart, "Long", "d".repeat(10_000), null).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> tooLong = postTicket(multipart, "Long", "d".repeat(10_001), null);
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mapper.readTree(tooLong.getBody()).get("message").asText())
                .isEqualTo("Description must be 10000 characters or fewer.");

        assertThat(postFeedback(json, "f".repeat(5_000)).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> longFeedback = postFeedback(json, "f".repeat(5_001));
        assertThat(longFeedback.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(longFeedback.getBody()).contains("Keep your feedback under 5,000 characters.");

        assertThat(count("SELECT coalesce(max(length(description)), 0) FROM support_tickets WHERE user_id = ?", customer.getId()))
                .isEqualTo(10_000);
    }

    @Test
    void aDescriptionTooBigToRead_isRefusedInSupportsWords_notTheStatementMessage() throws Exception {
        User customer = createCustomer(false);

        ResponseEntity<String> response = postTicket(bearerFor(customer, MediaType.MULTIPART_FORM_DATA), "Huge",
                "d".repeat(6 * 1024 * 1024), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.get("errorCode").asText()).isEqualTo("UPLOAD_TOO_LARGE");
        assertThat(body.get("message").asText()).contains("Attachments can be up to 5 MB").doesNotContain("statement");
    }

    @Test
    void anAttachmentNamedOutsideAscii_downloadsUnderItsName_andAnAsciiNameIsUnchanged() throws Exception {
        User customer = createCustomer(false);
        HttpHeaders multipart = bearerFor(customer, MediaType.MULTIPART_FORM_DATA);

        assertThat(downloadDisposition(customer, postTicket(multipart, "Shot", "See attached", "स्क्रीनशॉट रिपोर्ट.png")))
                .isEqualTo("attachment; filename=\"__________ _______.png\"; filename*=UTF-8''"
                        + "%E0%A4%B8%E0%A5%8D%E0%A4%95%E0%A5%8D%E0%A4%B0%E0%A5%80%E0%A4%A8%E0%A4%B6%E0%A5%89%E0%A4%9F"
                        + "%20%E0%A4%B0%E0%A4%BF%E0%A4%AA%E0%A5%8B%E0%A4%B0%E0%A5%8D%E0%A4%9F.png");
        assertThat(downloadDisposition(customer, postTicket(multipart, "Shot", "See attached", "Kontoauszug März.png")))
                .isEqualTo("attachment; filename=\"Kontoauszug M_rz.png\"; filename*=UTF-8''Kontoauszug%20M%C3%A4rz.png");
        assertThat(downloadDisposition(customer, postTicket(multipart, "Shot", "See attached", "shot.png")))
                .isEqualTo("attachment; filename=\"shot.png\"");
    }

    private String downloadDisposition(User customer, ResponseEntity<String> created) throws Exception {
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(created.getBody()).get("data");
        String path = "/api/v1/support/tickets/" + data.get("id").asText() + "/attachments/"
                + data.get("attachments").get(0).get("id").asText();
        ResponseEntity<byte[]> download = restTemplate.exchange(path, HttpMethod.GET,
                new HttpEntity<>(bearerFor(customer, MediaType.APPLICATION_JSON)), byte[].class);
        assertThat(download.getStatusCode()).isEqualTo(HttpStatus.OK);
        return download.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
    }

    /**
     * Holds an update of the customer's own row open on another connection for {@code holdMillis},
     * as a slow write elsewhere in the app would, and releases it on its own thread.
     */
    private Thread holdUserRow(User customer, long holdMillis) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.executeUpdate("UPDATE users SET updated_at = now() WHERE id = '" + customer.getId() + "'");
                held.countDown();
                Thread.sleep(holdMillis);
                connection.rollback();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        holder.start();
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        return holder;
    }

    /** Before the wait limit, a ticket waited as long as the row was held (7.07 s against an 8 s
     *  hold, measured). Now it gives up after 5 s with a message that keeps the form as it is. */
    @Test
    void aTicketBlockedLongerThanTheWaitLimit_isRefusedWithTryAgain_andNothingIsSaved() throws Exception {
        User customer = createCustomer(false);
        HttpHeaders multipart = bearerFor(customer, MediaType.MULTIPART_FORM_DATA);
        Thread holder = holdUserRow(customer, 9_000);

        long start = System.nanoTime();
        ResponseEntity<String> response = postTicket(multipart, "Help", "It broke", null);
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        holder.join();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mapper.readTree(response.getBody()).get("message").asText()).isEqualTo(SubmissionLock.BUSY_MESSAGE);
        assertThat(tookMillis).isBetween(4_500L, 8_000L);
        assertThat(count("SELECT count(*) FROM support_tickets WHERE user_id = ?", customer.getId())).isZero();
        // The next try, once the row is free, goes straight through.
        assertThat(postTicket(multipart, "Help", "It broke", null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aShortWait_stillSucceeds() throws Exception {
        User customer = createCustomer(false);
        Thread holder = holdUserRow(customer, 1_500);

        ResponseEntity<String> ticket = postTicket(bearerFor(customer, MediaType.MULTIPART_FORM_DATA), "Help", "It broke", null);
        holder.join();

        assertThat(ticket.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void feedbackBlockedLongerThanTheWaitLimit_isRefusedWithTryAgain() throws Exception {
        User customer = createCustomer(false);
        Thread holder = holdUserRow(customer, 9_000);

        ResponseEntity<String> response = postFeedback(bearerFor(customer, MediaType.APPLICATION_JSON), "Feedback");
        holder.join();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mapper.readTree(response.getBody()).get("message").asText()).isEqualTo(SubmissionLock.BUSY_MESSAGE);
        assertThat(count("SELECT count(*) FROM feedback_entries WHERE user_id = ?", customer.getId())).isZero();
    }
}
