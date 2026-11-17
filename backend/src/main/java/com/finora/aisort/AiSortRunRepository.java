package com.finora.aisort;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AiSortRunRepository extends JpaRepository<AiSortRun, UUID> {

    Optional<AiSortRun> findByUserIdAndImportsKey(UUID userId, String importsKey);

    Optional<AiSortRun> findByIdAndUserId(UUID id, UUID userId);

    /*
     * Every write after the run is saved is conditional: the worker's on its own attempt while the run
     * is RUNNING, the cut-off mark only on a RUNNING run gone quiet, a new try only on a stopped run.
     * A worker that was cut off, or whose run was tried again, changes nothing and sees 0 rows.
     */

    /** One payee worked, by the worker on {@code attempt}. 0 = no longer this worker's run: stop. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE AiSortRun r SET r.paymentsDone = r.paymentsDone + :done, r.paymentsSorted = r.paymentsSorted + :sorted,
               r.updatedAt = :at
        WHERE r.id = :id AND r.attempt = :attempt AND r.status = com.finora.aisort.AiSortRun.RunStatus.RUNNING
        """)
    int progress(@Param("id") UUID id, @Param("attempt") int attempt, @Param("done") int done,
                 @Param("sorted") int sorted, @Param("at") Instant at);

    /** The worker on {@code attempt} finishes its run. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE AiSortRun r SET r.status = :status, r.paymentsLeft = :left, r.updatedAt = :at, r.finishedAt = :at
        WHERE r.id = :id AND r.attempt = :attempt AND r.status = com.finora.aisort.AiSortRun.RunStatus.RUNNING
        """)
    int finish(@Param("id") UUID id, @Param("attempt") int attempt, @Param("status") AiSortRun.RunStatus status,
               @Param("left") Integer left, @Param("at") Instant at);

    /** A RUNNING run not updated since {@code before} was cut off (a restart): it is STOPPED. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE AiSortRun r SET r.status = com.finora.aisort.AiSortRun.RunStatus.STOPPED, r.updatedAt = :at, r.finishedAt = :at
        WHERE r.id = :id AND r.status = com.finora.aisort.AiSortRun.RunStatus.RUNNING AND r.updatedAt < :before
        """)
    int stopIfQuietSince(@Param("id") UUID id, @Param("before") Instant before, @Param("at") Instant at);

    /**
     * A stopped run is tried again: the next attempt, over the {@code remaining} payments still to sort.
     * The counts stay the import's: what earlier attempts sorted is kept as sorted and done, so the card
     * reads "Sorted 6" after 5 and then 1, not "Sorted 1".
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE AiSortRun r SET r.status = com.finora.aisort.AiSortRun.RunStatus.RUNNING, r.attempt = r.attempt + 1,
               r.paymentsTotal = r.paymentsSorted + :remaining, r.paymentsDone = r.paymentsSorted,
               r.paymentsLeft = NULL, r.updatedAt = :at, r.finishedAt = NULL
        WHERE r.id = :id AND r.status IN (com.finora.aisort.AiSortRun.RunStatus.STOPPED,
              com.finora.aisort.AiSortRun.RunStatus.STOPPED_LIMIT, com.finora.aisort.AiSortRun.RunStatus.STOPPED_AI_OFF)
        """)
    int tryAgain(@Param("id") UUID id, @Param("remaining") int remaining, @Param("at") Instant at);

    /** Whether the worker on {@code attempt} still owns a RUNNING run, checked before each payee. */
    @Query("""
        SELECT COUNT(r) > 0 FROM AiSortRun r
        WHERE r.id = :id AND r.attempt = :attempt AND r.status = com.finora.aisort.AiSortRun.RunStatus.RUNNING
        """)
    boolean stillRunning(@Param("id") UUID id, @Param("attempt") int attempt);

    /** Account purge (AccountPurgeSweepService). */
    @Modifying
    @Query("DELETE FROM AiSortRun r WHERE r.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
