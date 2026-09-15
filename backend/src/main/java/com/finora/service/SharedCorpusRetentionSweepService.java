package com.finora.service;

import com.finora.entity.Transaction;
import com.finora.repository.CounterpartyCategoryObservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Purges unpromoted observations past their retention window -- spec §4: 6 months at 1 distinct
 * voter, 12 months at 2. A promoted (shared_merchant_category) key is never touched here; its
 * observations are corroborated evidence, retained indefinitely. Bounded batches, same shape as
 * CounterpartyBackfillSweepService, so a large backlog can't hold one sweep run indefinitely.
 */
@Component
public class SharedCorpusRetentionSweepService {

    private static final Logger log = LoggerFactory.getLogger(SharedCorpusRetentionSweepService.class);
    private static final int BATCH_SIZE = 500;

    private final CounterpartyCategoryObservationRepository observations;

    public SharedCorpusRetentionSweepService(CounterpartyCategoryObservationRepository observations) {
        this.observations = observations;
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void sweep() {
        Instant cutoff1Voter = Instant.now().minus(180, ChronoUnit.DAYS);
        Instant cutoff2Voters = Instant.now().minus(365, ChronoUnit.DAYS);

        int purged = 0;
        while (true) {
            List<Object[]> expired = observations.findUnpromotedKeysPastRetention(
                    cutoff1Voter, cutoff2Voters, BATCH_SIZE);
            if (expired.isEmpty()) break;
            for (Object[] row : expired) {
                String counterpartyKey = (String) row[0];
                Transaction.Type direction = Transaction.Type.valueOf((String) row[1]);
                observations.deleteByCounterpartyKeyAndDirection(counterpartyKey, direction);
                purged++;
            }
            if (expired.size() < BATCH_SIZE) break;
        }
        if (purged > 0) {
            log.info("Shared corpus retention sweep purged {} unpromoted, expired observation key(s)", purged);
        }
    }
}
