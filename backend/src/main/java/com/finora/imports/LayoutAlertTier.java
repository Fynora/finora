package com.finora.imports;

import com.finora.dto.ImportDto.VerificationReport;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How much a layout-review alert deserves a person's attention right now -- see
 * docs/superpowers/specs/2026-11-06-layout-alert-severity-tiers-design.md.
 *
 * <p>{@link #DIGEST} is only ever a positive finding: every reason is one that is harmless on its
 * own, and a balance-chain warning is fully explained (gaps cancel, and re-ordering a same-day run
 * makes every disagreeing run reconcile). Everything else -- a failure, an unexplained warning, a
 * reason this class does not list, a missing or malformed detail -- is {@link #IMMEDIATE}, because
 * the cost of calling something urgent is one more email and the cost of the opposite is a real
 * problem waiting a day.
 *
 * <p>Reads only the structured booleans the validator already emits. No amount, date or narration.
 */
public enum LayoutAlertTier {
    IMMEDIATE,
    DIGEST;

    private static final String BALANCE_CHAIN_REASON = "VERIFICATION_NOT_PASSED:BALANCE_CHAIN";
    private static final Set<String> HARMLESS_ALONE = Set.of("NEW_LAYOUT", "HOLDER_NAME_UNREADABLE");

    /**
     * @param reasons the reasons on the alert; {@code reports} the verification reports of the run
     *                being recorded, one per section (entries may be null)
     */
    public static LayoutAlertTier of(Collection<String> reasons, List<VerificationReport> reports) {
        try {
            return judge(reasons, reports);
        } catch (RuntimeException e) {
            // Any surprise in the details is a reason to look, never a reason to wait.
            return IMMEDIATE;
        }
    }

    private static LayoutAlertTier judge(Collection<String> reasons, List<VerificationReport> reports) {
        if (reasons == null || reasons.isEmpty()) return IMMEDIATE;
        boolean balanceChain = false;
        for (String reason : reasons) {
            if (BALANCE_CHAIN_REASON.equals(reason)) balanceChain = true;
            else if (!HARMLESS_ALONE.contains(reason)) return IMMEDIATE;
        }
        if (!balanceChain) return DIGEST;

        List<LayoutReviewService.NotPassed> notPassed = LayoutReviewService.notPassedFindings(reports);
        if (notPassed.isEmpty()) return IMMEDIATE;
        for (LayoutReviewService.NotPassed found : notPassed) {
            if (!"BALANCE_CHAIN".equals(found.finding().rule())) return IMMEDIATE;
            if (!explained(found.finding().outcome(), found.finding().details())) return IMMEDIATE;
        }
        return DIGEST;
    }

    /** One rule for "this warning is only same-day order", shared with the finding itself and so with
     *  what the customer is shown. */
    private static boolean explained(String outcome, Map<String, Object> details) {
        return BalanceChainValidator.explainedBySameDayOrder(outcome, details);
    }
}
