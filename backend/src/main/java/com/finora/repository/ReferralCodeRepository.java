package com.finora.repository;

import com.finora.entity.ReferralCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ReferralCodeRepository extends JpaRepository<ReferralCode, UUID> {

    Optional<ReferralCode> findByUserId(UUID userId);

    Optional<ReferralCode> findByCode(String code);

    boolean existsByCode(String code);

    /** AccountPurgeSweepService. */
    void deleteByUserId(UUID userId);

    /**
     * ReferralService.redeemMilestone's actual concurrency guard -- a plain "read counter, check
     * >= required, then save(0)" would let two concurrent redeem requests (a double-click, two
     * tabs) both read the same pre-reset counter and both pass the check, creating two grants for
     * one threshold crossing. This UPDATE ... WHERE is the same "the mutation itself is the check"
     * pattern already used elsewhere in this codebase (WalletLedgerRepository
     * .insertReferralRewardIfAbsent's own doc comment explains why a prior SELECT is never enough)
     * -- under Postgres's read-committed row locking, a second concurrent UPDATE targeting the same
     * row blocks until the first commits, then re-evaluates this WHERE clause against the
     * now-already-reset counter and affects zero rows.
     *
     * @return 1 if the counter was actually >= required and got reset, 0 otherwise (not enough
     *         referrals, or a concurrent request already redeemed this milestone first).
     */
    @Modifying
    @Query("UPDATE ReferralCode c SET c.plusMilestoneCounter = 0 WHERE c.userId = :userId AND c.plusMilestoneCounter >= :required")
    int resetPlusCounterIfAtLeast(@Param("userId") UUID userId, @Param("required") int required);

    /** Same guard as {@link #resetPlusCounterIfAtLeast}, for the independent Premium counter. */
    @Modifying
    @Query("UPDATE ReferralCode c SET c.premiumMilestoneCounter = 0 WHERE c.userId = :userId AND c.premiumMilestoneCounter >= :required")
    int resetPremiumCounterIfAtLeast(@Param("userId") UUID userId, @Param("required") int required);
}
