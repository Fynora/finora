package com.finora.imports.send;

import com.finora.config.RateLimiter;
import com.finora.config.RequestMetadata;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.StatementUpload;
import com.finora.imports.jobs.ImportJobService;
import com.finora.service.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Gate 2b spec §7: send a statement from your computer.
 *
 * <p><b>An upload code, not a sign-in.</b> The phone asks for a code; the public page
 * ({@code app.fynora.net/send}) takes the code and one file, which goes through the normal queued
 * import for the code's owner. The computer sees nothing of the account. The worst a stolen code can
 * do is put one file into its owner's review queue during its 10 minutes, and nothing is saved until
 * the owner confirms it on their phone.
 *
 * <h2>The code</h2>
 * <ul>
 *   <li>6 characters from {@link #ALPHABET}, which has no I, L, O, 0 or 1, drawn with SecureRandom.</li>
 *   <li>Valid for 10 minutes, used once, tied to the user who asked for it. A new code cancels the
 *       user's open ones.</li>
 *   <li>Only its SHA-256 is stored.</li>
 *   <li>Issuing and using are audited (user, time, address, via RequestMetadata).</li>
 * </ul>
 *
 * <h2>Guessing</h2>
 * Five codes that didn't work from one address within an hour block that address until the hour is
 * up, checked BEFORE the next code is tried, so a blocked guesser cannot land a right guess
 * ({@link RateLimiter#isAtLimit}). A wrong, expired, used or cancelled code all get one answer,
 * {@link ErrorCode#SEND_CODE_INVALID}, so an answer never tells a guesser which codes exist. The
 * per-address request limit on the endpoint is in RateLimitFilter ({@code send-statement}).
 *
 * <h2>Times</h2>
 * Every time comparison runs in the database ({@code now()}), so issuing, claiming and the phone's
 * status all read one clock.
 */
@Service
public class StatementSendService {

    /** No I, L, O, 0 or 1: nothing a person could read as another character. */
    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final int LENGTH = 6;

    private final JdbcTemplate jdbc;
    private final ImportJobService importJobService;
    private final AuditService auditService;
    private final RequestMetadata requestMetadata;
    private final RateLimiter failureLimiter;
    private final SecureRandom random = new SecureRandom();

    public StatementSendService(JdbcTemplate jdbc, ImportJobService importJobService, AuditService auditService,
                                RequestMetadata requestMetadata, StringRedisTemplate redisTemplate,
                                @Value("${app.rate-limit.send-code-failure.max:5}") int failureMax,
                                @Value("${app.rate-limit.send-code-failure.window-seconds:3600}") int failureWindow) {
        this.jdbc = jdbc;
        this.importJobService = importJobService;
        this.auditService = auditService;
        this.requestMetadata = requestMetadata;
        this.failureLimiter = new RateLimiter(failureMax, failureWindow, "send-code-failure", redisTemplate);
    }

    /** A claimed code: whose it is, while its file is checked and queued. */
    public record Claim(UUID codeId, UUID userId) {}

    static String newCode(SecureRandom random) {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /** What the user typed, as the code it stands for: spaces and dashes dropped, case ignored. */
    static String normalise(String typed) {
        return typed == null ? "" : typed.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /**
     * A new code for the user, cancelling their open ones. Refused with 503 when this deployment
     * cannot accept a queued upload (no object storage): the file would have nowhere to go. The same
     * gate as {@link ImportJobService#accept}; the phone shows the option only when the import job
     * availability says queued uploads are on.
     */
    public SendDto.CodeIssued issue(UUID userId) {
        if (!importJobService.acceptsUploads()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Sending a statement from a computer isn't available right now.");
        }
        jdbc.update("UPDATE statement_send_codes SET cancelled_at = now() "
                + "WHERE user_id = ? AND used_at IS NULL AND cancelled_at IS NULL", userId);
        for (int attempt = 0; attempt < 10; attempt++) {
            String code = newCode(random);
            UUID id = UUID.randomUUID();
            // ON CONFLICT rather than catching the unique violation: a failed INSERT would abort a
            // caller's transaction. A collision with another live code just draws again.
            List<Instant> expires = jdbc.query("""
                    INSERT INTO statement_send_codes (id, user_id, code_hash, created_at, expires_at)
                    VALUES (?, ?, ?, now(), now() + interval '10 minutes')
                    ON CONFLICT (code_hash) WHERE used_at IS NULL AND cancelled_at IS NULL DO NOTHING
                    RETURNING expires_at
                    """, (rs, i) -> rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                    id, userId, hash(code));
            if (!expires.isEmpty()) {
                auditService.record(userId, "STATEMENT_SEND_CODE_ISSUED", "StatementSendCode", id,
                        requestMetadata.addTo(new HashMap<>()));
                return new SendDto.CodeIssued(code, expires.get(0));
            }
        }
        throw new IllegalStateException("Could not draw a free send code in 10 attempts");
    }

    /** The phone's view of its latest code. */
    public SendDto.CodeStatus current(UUID userId) {
        List<SendDto.CodeStatus> rows = jdbc.query("""
                SELECT CASE
                         WHEN cancelled_at IS NOT NULL THEN 'NONE'
                         WHEN used_at IS NOT NULL AND import_job_id IS NOT NULL THEN 'RECEIVED'
                         WHEN used_at IS NOT NULL THEN 'WAITING'
                         WHEN expires_at <= now() THEN 'EXPIRED'
                         ELSE 'WAITING'
                       END AS state, import_job_id
                  FROM statement_send_codes WHERE user_id = ?
                 ORDER BY created_at DESC LIMIT 1
                """, (rs, i) -> new SendDto.CodeStatus(rs.getString("state"), uuid(rs, "import_job_id")), userId);
        return rows.isEmpty() ? new SendDto.CodeStatus("NONE", null) : rows.get(0);
    }

    /**
     * Claims a code for one upload, atomically: of two uploads with one code, exactly one gets it.
     * A code that doesn't work counts against the caller's address.
     */
    public Claim claim(String typedCode) {
        String ip = requestMetadata.ip();
        String key = ip == null ? "unknown" : ip;
        if (failureLimiter.isAtLimit(key)) {
            throw new ApiException(ErrorCode.SEND_CODE_BLOCKED);
        }
        String code = normalise(typedCode);
        List<Claim> claimed = code.length() != LENGTH ? List.of() : jdbc.query("""
                UPDATE statement_send_codes SET used_at = now()
                 WHERE code_hash = ? AND used_at IS NULL AND cancelled_at IS NULL AND expires_at > now()
                RETURNING id, user_id
                """, (rs, i) -> new Claim(uuid(rs, "id"), uuid(rs, "user_id")), hash(code));
        if (claimed.isEmpty()) {
            failureLimiter.allow(key);
            throw new ApiException(ErrorCode.SEND_CODE_INVALID);
        }
        return claimed.get(0);
    }

    /** A refused or failed upload gives the code back, usable again until it expires. */
    public void release(Claim claim) {
        jdbc.update("UPDATE statement_send_codes SET used_at = NULL WHERE id = ? AND import_job_id IS NULL",
                claim.codeId());
    }

    /**
     * Queues a checked file for the code's owner and records the job against the code. Called with
     * no transaction open: {@link ImportJobService#accept} must not hold a connection across the
     * upload to storage (BH-018). If queuing fails the code is given back.
     */
    public void complete(Claim claim, MultipartFile file, StatementUpload.Format format) throws IOException {
        UUID jobId;
        try {
            jobId = importJobService.accept(claim.userId(), file, format, null).getId();
        } catch (IOException | RuntimeException e) {
            release(claim);
            throw e;
        }
        jdbc.update("UPDATE statement_send_codes SET import_job_id = ? WHERE id = ?", jobId, claim.codeId());
        Map<String, Object> metadata = requestMetadata.addTo(new HashMap<>(Map.of("importJobId", jobId.toString())));
        auditService.record(claim.userId(), "STATEMENT_SEND_CODE_USED", "StatementSendCode", claim.codeId(), metadata);
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }
}
