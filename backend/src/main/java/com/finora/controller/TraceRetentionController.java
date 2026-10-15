package com.finora.controller;

import com.finora.imports.blanked.TraceRetentionService;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Tells the private trace repository's nightly job which held-item references no longer exist, so
 * it can delete traces made from a customer who has since deleted their account
 * (docs/engineering/private-statement-traces.md).
 *
 * <p>Machine-to-machine: a dedicated key in {@code X-Trace-Retention-Key}, compared in constant
 * time, in place of a user session -- SecurityConfig permits the path the way it does the payment
 * webhooks. Off, answering 404, unless {@code app.trace-retention.key} is set. At most 500
 * references a call, and the answer is a subset of the question: no user, email or file name is
 * ever returned or logged. Nothing is looked up for a caller without the key.
 *
 * <p>A configured key shorter than {@link #MIN_KEY_LENGTH} characters leaves the endpoint off and
 * logs a warning (never the key): there is no rate limit here, so the key's length is what makes
 * guessing it hopeless.
 *
 * <p>{@code @Hidden}: infrastructure, not product API, like {@link HealthController}; no client
 * is generated from it.
 */
@Hidden
@RestController
@RequestMapping("/api/v1/internal/trace-retention")
public class TraceRetentionController {

    private static final Logger log = LoggerFactory.getLogger(TraceRetentionController.class);

    static final int MAX_REFERENCES = 500;
    static final int MIN_KEY_LENGTH = 32;

    private final TraceRetentionService service;
    private final byte[] key;   // empty = off

    public TraceRetentionController(TraceRetentionService service,
                                    @Value("${app.trace-retention.key:}") String key) {
        this.service = service;
        if (!key.isEmpty() && key.length() < MIN_KEY_LENGTH) {
            log.warn("app.trace-retention.key is shorter than {} characters; the trace-retention endpoint stays off",
                    MIN_KEY_LENGTH);
            key = "";
        }
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    public record TraceRetentionQuestion(List<String> references) {}
    public record TraceRetentionAnswer(List<String> gone) {}

    @PostMapping("/gone")
    public ResponseEntity<TraceRetentionAnswer> gone(
            @RequestHeader(value = "X-Trace-Retention-Key", required = false) String given,
            @RequestBody(required = false) TraceRetentionQuestion body) {
        if (key.length == 0) return ResponseEntity.notFound().build();
        if (given == null || !MessageDigest.isEqual(key, given.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<String> refs = body == null || body.references() == null ? List.of() : body.references();
        if (refs.size() > MAX_REFERENCES) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(new TraceRetentionAnswer(service.gone(refs)));
    }
}
