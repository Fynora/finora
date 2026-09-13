package com.finora.service;

/**
 * Governance metadata for one Fyn-callable tool -- plan §4.1. A descriptor, not an execution
 * contract: Phase 2-4 tools each define their own concrete method signature (their inputs differ
 * too much to force through one shared shape today -- {@code getBalance} takes a user id,
 * {@code getSpendByCategory} takes a category and a month), and register one of these alongside it
 * purely so {@link FynToolRegistry} has one place to answer "what can Fyn call, under what
 * entitlement, at what data tier" for auditing and future governance UI.
 *
 * @param name               e.g. {@code GET_BALANCE}
 * @param requiredEntitlement one of {@code FeatureEntitlement}'s Fyn keys, or {@code null} for a
 *                            tool with no per-user entitlement check (e.g. Phase 2's admin-only
 *                            import-diagnosis assist)
 * @param maxDataTier        the ceiling on what this tool is allowed to return -- enforced by the
 *                            tool's own return type, not by this record; this is the declared,
 *                            audited claim
 * @param auditEnabled       whether every call is written to {@code ai_audit_log} -- {@code true}
 *                            for every financial tool planned so far (plan §4.1 says so explicitly)
 */
public record FynToolDescriptor(
        String name,
        String description,
        String requiredEntitlement,
        FynDataTier maxDataTier,
        boolean auditEnabled) {

    public FynToolDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A Fyn tool must have a name.");
        }
        if (maxDataTier == null) {
            throw new IllegalArgumentException("A Fyn tool must declare a maxDataTier.");
        }
    }
}
