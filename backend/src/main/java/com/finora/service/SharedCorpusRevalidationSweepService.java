package com.finora.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-evaluates Revalidating rows whose 90-day cooldown elapsed with no further observation to
 * trigger SharedCorpusService's reactive check -- spec §7's "or just time, with nothing further
 * disagreeing" exit path. Without this, a key that stops seeing transactions after a contradiction
 * would stay stuck Revalidating (never suggested again) indefinitely. Bounded batches, same shape
 * as SharedCorpusRetentionSweepService.
 */
@Component
public class SharedCorpusRevalidationSweepService {

    private static final Logger log = LoggerFactory.getLogger(SharedCorpusRevalidationSweepService.class);
    private static final int BATCH_SIZE = 500;

    private final SharedCorpusService sharedCorpusService;

    public SharedCorpusRevalidationSweepService(SharedCorpusService sharedCorpusService) {
        this.sharedCorpusService = sharedCorpusService;
    }

    @Scheduled(cron = "0 45 3 * * *")
    public void sweep() {
        int reevaluated = 0;
        while (true) {
            int n = sharedCorpusService.reevaluateTimedOutRevalidations(BATCH_SIZE);
            if (n == 0) break;
            reevaluated += n;
            if (n < BATCH_SIZE) break;
        }
        if (reevaluated > 0) {
            log.info("Shared corpus revalidation sweep re-evaluated {} timed-out Revalidating row(s)", reevaluated);
        }
    }
}
