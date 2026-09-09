package com.finora.repository;

import com.finora.entity.WalletLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface WalletLedgerRepository extends JpaRepository<WalletLedgerEntry, UUID> {

    List<WalletLedgerEntry> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Proposal §4: balance is a computed SUM over this table, never a stored field.
     *  {@code COALESCE} because {@code SUM} over zero rows is SQL {@code NULL}, not zero -- a user
     *  with no ledger entries yet must see a real 0, not a null balance. */
    @Query("SELECT COALESCE(SUM(w.amount), 0) FROM WalletLedgerEntry w WHERE w.userId = :userId")
    BigDecimal sumAmountByUserId(@Param("userId") UUID userId);

    /** AccountPurgeSweepService. */
    void deleteByUserId(UUID userId);

    /**
     * Inserts the referral-reward wallet entry, or does nothing if {@code referenceId} already has
     * one (V168's partial unique index) -- see {@code ReferralService.creditReward}'s own doc
     * comment for the check-then-act race this exists to survive, same reasoning as
     * {@code MerchantAliasRepository#insertIfAbsent}/{@code NotificationRepository#insertIfAbsent}
     * for why a plain {@code save()} + a Java-side status check is not enough and why
     * {@code catch(DataIntegrityViolationException)} is the wrong tool (it does not, by itself,
     * keep the rest of the caller's transaction usable after a failed statement).
     *
     * @return 1 if this call inserted the row, 0 if a reward for this referral already existed --
     *         from an earlier call, or a concurrent writer that got there first.
     */
    @Modifying
    @Query(value = """
           INSERT INTO wallet_ledger (id, user_id, amount, reason, reference_id, created_at)
           VALUES (gen_random_uuid(), :userId, :amount, 'REFERRAL_REWARD', :referenceId, now())
           ON CONFLICT (reference_id) WHERE reason = 'REFERRAL_REWARD' DO NOTHING
           """, nativeQuery = true)
    int insertReferralRewardIfAbsent(@Param("userId") UUID userId, @Param("amount") BigDecimal amount,
                                      @Param("referenceId") UUID referenceId);
}
