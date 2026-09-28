package com.finora.imports.analysis;

import com.finora.dto.ImportDto.DetectedAccountInfo;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Which bank, and which kind of statement, the engine read a document as -- so an analysis row
 * can be recognised by an admin without opening it.
 *
 * <p>Both values are the engine's own detection ({@code BankRegistry.detect} and Financial Product
 * Discovery), copied from the staged {@link DetectedAccountInfo}. Neither is ever guessed: a bank
 * the registry did not recognise ({@code id "OTHER"}) is recorded as null, not as the
 * "Bank Statement Import" display fallback, and an {@code UNKNOWN} product is left out. A null
 * here therefore means "not identified", which is a finding in its own right.
 *
 * <p>Not personal data: a bank's official name and a product type, never an account number, a
 * holder name or the file name -- the analysis table's "structure and outcome only" boundary holds.
 *
 * @param checked       whether detection ran at all. {@code false} for a document that failed
 *                      before any section was staged (a wrong password, say) -- so "the engine
 *                      looked and recognised no bank" and "the engine never got that far" stay
 *                      two different answers, the same distinction the row count keeps.
 * @param bankName      the recognised bank's official name, or null
 * @param statementType the distinct identified product types in section order, comma-joined
 *                      (e.g. {@code "SAVINGS,FIXED_DEPOSIT"}), or null
 */
public record DocumentIdentity(boolean checked, String bankName, String statementType) {

    /** Detection never ran. */
    public static final DocumentIdentity NONE = new DocumentIdentity(false, null, null);

    private static final String UNRECOGNISED_BANK_ID = "OTHER";
    private static final String UNKNOWN_PRODUCT = "UNKNOWN";
    /** Column widths from V239. Truncated rather than failing the evidence write. */
    private static final int BANK_MAX = 128;
    private static final int TYPE_MAX = 64;

    /** From every staged section's detection. A composite statement's sections share one bank. */
    public static DocumentIdentity of(List<DetectedAccountInfo> detected) {
        if (detected == null || detected.isEmpty()) return NONE;
        String bank = null;
        Set<String> types = new LinkedHashSet<>();
        boolean sawDetection = false;
        for (DetectedAccountInfo info : detected) {
            if (info == null) continue;
            sawDetection = true;
            if (bank == null && info.bank() != null && !UNRECOGNISED_BANK_ID.equals(info.bank().id())) {
                bank = blankToNull(info.bank().officialName());
            }
            String product = blankToNull(info.detectedProduct());
            if (product != null && !UNKNOWN_PRODUCT.equals(product)) types.add(product);
        }
        if (!sawDetection) return NONE;
        String type = types.isEmpty() ? null : String.join(",", types);
        return new DocumentIdentity(true, truncate(bank, BANK_MAX), truncate(type, TYPE_MAX));
    }

    public static DocumentIdentity of(DetectedAccountInfo detected) {
        return detected == null ? NONE : of(java.util.Collections.singletonList(detected));
    }

    public static DocumentIdentity ofSections(List<com.finora.dto.ImportDto.StagedAccountSection> sections) {
        if (sections == null) return NONE;
        return of(sections.stream().filter(Objects::nonNull)
                .map(com.finora.dto.ImportDto.StagedAccountSection::detectedAccount).toList());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
