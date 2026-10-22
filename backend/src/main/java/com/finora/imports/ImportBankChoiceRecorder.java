package com.finora.imports;

import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.util.BankRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * One row per account a statement import creates (V285 {@code import_bank_choice}): where its bank
 * came from at staging and what bank the account ended up with. Feeds the admin counts of users'
 * bank choices per layout and of bank origins per week (spec 2026-10-22, sections 6 and 8).
 *
 * <p>Deliberately records no user or account: those admin surfaces show counts only.
 */
@Component
public class ImportBankChoiceRecorder {

    private final JdbcTemplate jdbc;

    public ImportBankChoiceRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param chosenBankId the bank the created account actually has (after the account service
     *                     resolved whatever the client sent), never the raw request value
     */
    public void record(String layoutFingerprint, DetectedAccountInfo detected, String chosenBankId) {
        String detectedBank = detected == null || detected.bank() == null ? BankRegistry.UNKNOWN_ID : detected.bank().id();
        String source = detected == null ? DetectedAccountInfo.BANK_SOURCE_NONE
                : detected.bankSource() != null ? detected.bankSource()
                : DetectedAccountInfo.sourceOf(detected.bank()); // staged before bankSource existed
        jdbc.update("""
                INSERT INTO import_bank_choice (id, layout_fingerprint, detected_bank_id, bank_source, chosen_bank_id, created_at)
                VALUES (?, ?, ?, ?, ?, now())
                """, UUID.randomUUID(), layoutFingerprint, detectedBank, source,
                chosenBankId == null ? BankRegistry.UNKNOWN_ID : chosenBankId);
    }
}
