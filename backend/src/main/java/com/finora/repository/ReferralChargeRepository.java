package com.finora.repository;

import com.finora.entity.ReferralCharge;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ReferralChargeRepository extends JpaRepository<ReferralCharge, UUID> {

    /**
     * ReferralService.onChargeReversed. Row-locked so two reversals of one charge (a refund and a
     * lost chargeback, or one event re-sent under a new event id) serialize: the second waits for
     * the first to commit, and Postgres then hands it the row as the first left it, with
     * reversed_at already set, so it does nothing. Requires an active transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ReferralCharge c WHERE c.provider = :provider AND c.chargeRef = :chargeRef")
    Optional<ReferralCharge> findForUpdate(@Param("provider") String provider, @Param("chargeRef") String chargeRef);

    /**
     * ReferralService.onChargeReversed, for a refund whose charge has no row yet: records the
     * charge id as already reversed, so the charge -- if its webhook is only late, not missing --
     * can never count when it does arrive. ON CONFLICT DO NOTHING rather than a plain insert: if a
     * concurrent transaction is inserting the charge's own row, this waits for it and then inserts
     * nothing (returns 0), and the caller re-reads the now-committed row instead.
     */
    @Modifying
    @Query(value = "INSERT INTO referral_charges (provider, charge_ref, counted, reversed_at, reversal_reason) "
            + "VALUES (:provider, :chargeRef, false, now(), :reason) "
            + "ON CONFLICT (provider, charge_ref) DO NOTHING", nativeQuery = true)
    int insertReversedIfAbsent(@Param("provider") String provider, @Param("chargeRef") String chargeRef,
                               @Param("reason") String reason);

    boolean existsByProviderAndChargeRef(String provider, String chargeRef);

    /** AccountPurgeSweepService -- the purged user's rows as a referrer. Rows where the purged user
     *  was the one referred are deliberately kept (see V241). */
    void deleteByReferrerUserId(UUID referrerUserId);
}
