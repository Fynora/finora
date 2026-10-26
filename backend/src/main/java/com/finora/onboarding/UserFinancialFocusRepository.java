package com.finora.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UserFinancialFocusRepository extends JpaRepository<UserFinancialFocus, UUID> {
    List<UserFinancialFocus> findByUserId(UUID userId);

    @Modifying
    @Query("DELETE FROM UserFinancialFocus f WHERE f.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);

    /** Every focus row of this user except {@code keep} -- with {@link #insertIfAbsent}, how
     *  OnboardingService saves a new set of answers without deleting the ones that stay. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM UserFinancialFocus f WHERE f.userId = :userId AND f.focusKey NOT IN :keep")
    void deleteByUserIdExcept(@Param("userId") UUID userId, @Param("keep") java.util.Collection<String> keep);

    /** One focus answer, unless the user already has it. ON CONFLICT on UNIQUE(user_id, focus_key)
     *  (V162): two saves of the same answers at once each wrote it, and the loser's insert failed as
     *  a 409 "refresh and try again" -- 6 of 10 in a live test. The database settles the race now. */
    @Modifying
    @Query(value = "INSERT INTO user_financial_focus (user_id, focus_key) VALUES (:userId, :key) "
            + "ON CONFLICT (user_id, focus_key) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId, @Param("key") String key);
}
