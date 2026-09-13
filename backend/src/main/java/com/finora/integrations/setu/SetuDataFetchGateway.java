package com.finora.integrations.setu;

import java.time.LocalDate;

/**
 * The only Setu-facing seam the transaction-sync pipeline depends on -- mirrors SetuConsentGateway's
 * role for the link-lifecycle side (Plan 1). SetuDataFetchService programs against this interface
 * only, never against an HTTP client directly, so it's unit-testable with a hand-rolled fake.
 */
public interface SetuDataFetchGateway {

    boolean isConfigured();

    /** @param from inclusive, @param to inclusive -- the trailing window being (re-)fetched. Plan 2
     *              only ever calls this with the initial 3-month backfill range or the webhook's
     *              implied "since last successful sync" range; a genuinely sliding, overlapping
     *              re-fetch window is Plan 6's concern, not this method's contract today. */
    SetuFiDataFetchResult fetchTransactions(String consentHandleId, LocalDate from, LocalDate to);
}
