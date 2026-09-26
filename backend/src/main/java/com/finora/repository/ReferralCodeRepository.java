package com.finora.repository;

import com.finora.entity.ReferralCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ReferralCodeRepository extends JpaRepository<ReferralCode, UUID> {

    Optional<ReferralCode> findByUserId(UUID userId);

    /**
     * ReferralService's milestone increment. Row-locked because the increment is read, +1, save:
     * without the lock, a friend's subscription landing while the referrer redeems could read the
     * pre-redemption count and write it back (+1) after {@link #consumeMilestoneIfAtLeast}, so the
     * redeemed referrals would count again; and two friends subscribing at once would both read
     * the same count and lose one. {@link #consumeMilestoneIfAtLeast}'s UPDATE takes the same row
     * lock, so the two serialize. Requires an active transaction (onPlanChanged is @Transactional).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ReferralCode c WHERE c.userId = :userId")
    Optional<ReferralCode> findByUserIdForUpdate(@Param("userId") UUID userId);

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
     * <p>Subtracts {@code required} rather than resetting to 0: someone who reaches 14 before
     * redeeming keeps the other 7 toward a second month instead of losing them.
     *
     * @return 1 if the counter was actually >= required and got reduced, 0 otherwise (not enough
     *         referrals, or a concurrent request already redeemed this milestone first).
     */
    @Modifying
    @Query("UPDATE ReferralCode c SET c.premiumMilestoneCounter = c.premiumMilestoneCounter - :required "
            + "WHERE c.userId = :userId AND c.premiumMilestoneCounter >= :required")
    int consumeMilestoneIfAtLeast(@Param("userId") UUID userId, @Param("required") int required);
}
