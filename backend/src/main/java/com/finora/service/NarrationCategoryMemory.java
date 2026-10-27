package com.finora.service;

import com.finora.util.CategoryRules;
import com.finora.util.MerchantNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The category a person chose for a row that names nobody, recalled for the next row with the same
 * text ({@code narration_category_memory}, V295).
 *
 * <p>Such a row has no merchant ({@link MerchantNames#narrationNamesNobody}), so merchant learning
 * cannot carry a choice to the next one. Most never repeat -- each bare UPI reference is new, so a
 * choice for one is never recalled for another, and unrelated payees are not pooled. Some do repeat:
 * a NACH debit that prints only its mandate number comes back every month, and withdrawals at one
 * ATM share its place. The key is therefore:
 * <ul>
 *   <li>for a cash withdrawal, the name its merchant used to get ({@link MerchantNames#forNarration},
 *       which drops the per-withdrawal reference and keeps the place), as merchant matching grouped
 *       those rows before;</li>
 *   <li>for anything else, the whole normalised narration, reference numbers included, as the exact
 *       alias matched those rows before.</li>
 * </ul>
 * Only a SHA-256 of the key is stored, never the narration. The latest choice wins.
 */
@Component
public class NarrationCategoryMemory {

    private static final Logger log = LoggerFactory.getLogger(NarrationCategoryMemory.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate afterCommitWrites;

    public NarrationCategoryMemory(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.afterCommitWrites = new TransactionTemplate(transactionManager);
        // afterCommit still has the finished transaction's resources bound; a new one is required.
        this.afterCommitWrites.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The key a narration is remembered under, or null for one that names somebody. */
    static String keyFor(String description) {
        if (!MerchantNames.narrationNamesNobody(description)) return null;
        String text = MerchantNames.isCashWithdrawal(description)
                ? "cash " + CategoryRules.normalize(MerchantNames.forNarration(description))
                : "text " + CategoryRules.normalize(description);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }

    /** The category last chosen for a row with this narration's key, if any. */
    public Optional<UUID> recall(UUID userId, String description) {
        String key = keyFor(description);
        if (userId == null || key == null) return Optional.empty();
        List<UUID> found = jdbc.queryForList(
                "SELECT category_id FROM narration_category_memory WHERE user_id = ? AND key_hash = ?",
                UUID.class, userId, key);
        return found.stream().findFirst();
    }

    /**
     * Keeps this choice for the narration's key -- after the caller's transaction commits, in a
     * transaction of its own, as merchant learning is queued rather than written in the import.
     * Written inside an import, two imports filing the same texts in a different order could
     * deadlock on these rows and one import would fail; written after, a failure here costs only
     * the memory, is logged, and never reaches the person's import or edit. A rolled-back caller
     * remembers nothing. Outside a transaction it is written at once.
     */
    public void remember(UUID userId, String description, UUID categoryId) {
        String key = keyFor(description);
        if (userId == null || key == null || categoryId == null) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            write(userId, key, categoryId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    afterCommitWrites.executeWithoutResult(tx -> write(userId, key, categoryId));
                } catch (RuntimeException e) {
                    log.warn("Could not remember a category for a row that names nobody (user {}): {}", userId, e.toString());
                }
            }
        });
    }

    private void write(UUID userId, String key, UUID categoryId) {
        // An upsert: two writers of one key queue on the row instead of failing on the primary key.
        // A category deleted in the meantime fails the foreign key, which the caller above logs.
        jdbc.update("""
                INSERT INTO narration_category_memory (user_id, key_hash, category_id)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id, key_hash) DO UPDATE SET category_id = EXCLUDED.category_id, updated_at = now()
                """, userId, key, categoryId);
    }

    /** Removes every remembered choice of one user (account purge). */
    public int forgetUser(UUID userId) {
        return jdbc.update("DELETE FROM narration_category_memory WHERE user_id = ?", userId);
    }
}
