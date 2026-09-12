package com.finora.repository;

import com.finora.entity.NetWorthSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NetWorthSnapshotRepository extends JpaRepository<NetWorthSnapshot, UUID> {
    List<NetWorthSnapshot> findByUserIdOrderBySnapshotDateAsc(UUID userId);
    Optional<NetWorthSnapshot> findByUserIdAndSnapshotDate(UUID userId, LocalDate date);

    /** Identity Engine (design spec's Layer 1 Timeline): the net worth figure immediately before
     *  today's write, read BEFORE upsertForToday runs so a milestone-crossing check has a real
     *  "before" value to compare against -- see NetWorthService.recordNetWorthMilestoneIfCrossed. */
    Optional<NetWorthSnapshot> findTopByUserIdOrderBySnapshotDateDesc(UUID userId);

    /** AccountPurgeSweepService -- hard delete, no soft-delete concern on this entity. */
    void deleteByUserId(UUID userId);

    /** DashboardRangeService: the balance AS OF a given date -- the nearest snapshot at or before
     *  it, since a snapshot table only ever has entries for days someone actually saved one (or a
     *  sweep ran), not every calendar day. Used for both the current range's ending balance and
     *  the previous range's, so a delta compares two real historical figures rather than summing
     *  balance across months (which isn't a meaningful quantity -- see that service's own doc
     *  comment). */
    Optional<NetWorthSnapshot> findFirstByUserIdAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(
            UUID userId, LocalDate date);

    /** TimelineEventService's backfill for NET_WORTH_10K/NET_WORTH_100K: the earliest snapshot
     *  date on which this user's net worth was already at or above {@code threshold}, or null if
     *  none is. Without this, a user already above a threshold before the timeline feature
     *  shipped would never see that milestone -- the live trigger in NetWorthService only fires
     *  on an upward CROSSING between two snapshots, which for them already happened, silently,
     *  before this table's rows existed to compare against. Best-effort: a user with no snapshot
     *  history yet (sweep hasn't run for them) gets nothing backfilled and instead picks the
     *  milestone up naturally the next time snapshotForTodayOnly detects a real crossing. */
    @Query(value = "SELECT MIN(snapshot_date) FROM net_worth_snapshots WHERE user_id = :userId AND net_worth >= :threshold",
           nativeQuery = true)
    LocalDate findEarliestSnapshotDateAtOrAbove(@Param("userId") UUID userId, @Param("threshold") BigDecimal threshold);

    /**
     * Writes today's snapshot -- inserting it, or overwriting the figures on the row already there
     * for {@code (user_id, snapshot_date)} -- as one atomic statement. See
     * {@code NetWorthService.saveSnapshotForToday}'s doc comment for the find-then-save race this
     * replaces, and why leaving that race in place stopped being an acceptable trade the moment it
     * was noticed: it happened to be harmless today only because the calling method carries no
     * {@code @Transactional}, which is exactly the load-bearing accident
     * {@code MerchantNormalizationEngine.addAlias}'s own doc comment describes losing.
     *
     * <p>Same {@code INSERT ... ON CONFLICT DO UPDATE} shape as
     * {@link RegisteredLayoutRepository#observe}, for the same reason: the database resolves the
     * conflict atomically and silently, so two concurrent "save today's snapshot" calls -- a
     * double-click, or a retried request -- can never both attempt the INSERT and never raise an
     * exception either way, regardless of what transaction boundary either caller runs inside.
     *
     * <p>{@code REQUIRES_NEW}, and declared here for the same reason {@code observe} is: a
     * {@code @Modifying} query needs SOME active transaction to run in at all, and {@code
     * saveSnapshotForToday} deliberately carries no {@code @Transactional} of its own (nor does its
     * caller) -- there is no ambient transaction here to join. {@code REQUIRES_NEW} rather than
     * merely {@code @Transactional} so that stays true even if a future caller wraps this in one:
     * this write keeps its own short, self-contained transaction regardless, which is the whole
     * point of closing the race atomically rather than relying on nothing upstream ever becoming
     * transactional.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(value = """
           INSERT INTO net_worth_snapshots
               (id, user_id, snapshot_date, total_assets, total_liabilities, net_worth)
           VALUES
               (gen_random_uuid(), :userId, :snapshotDate, :totalAssets, :totalLiabilities, :netWorth)
           ON CONFLICT (user_id, snapshot_date) DO UPDATE SET
               total_assets      = EXCLUDED.total_assets,
               total_liabilities = EXCLUDED.total_liabilities,
               net_worth         = EXCLUDED.net_worth
           """, nativeQuery = true)
    void upsertForToday(@Param("userId") UUID userId, @Param("snapshotDate") LocalDate snapshotDate,
                         @Param("totalAssets") BigDecimal totalAssets,
                         @Param("totalLiabilities") BigDecimal totalLiabilities,
                         @Param("netWorth") BigDecimal netWorth);
}
