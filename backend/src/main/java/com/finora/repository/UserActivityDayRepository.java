package com.finora.repository;

import com.finora.entity.UserActivityDay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface UserActivityDayRepository extends JpaRepository<UserActivityDay, UUID> {

    List<UserActivityDay> findByUserIdOrderByActivityDateAsc(UUID userId);

    /** Idempotent insert -- a second request on the same day, from this instance or another one,
     *  is a no-op rather than a unique-constraint failure. Its own transaction because the caller
     *  (UserActivityInterceptor) runs before any controller, outside every service transaction. */
    @Transactional
    @Modifying
    @Query(value = "INSERT INTO user_activity_days (user_id, activity_date) "
            + "VALUES (:userId, :activityDate) "
            + "ON CONFLICT (user_id, activity_date) DO NOTHING",
            nativeQuery = true)
    void recordDay(@Param("userId") UUID userId, @Param("activityDate") LocalDate activityDate);

    /** AccountPurgeSweepService -- {@code user_id} carries {@code ON DELETE CASCADE} to {@code
     *  users(id)} (V249), but that never fires: this flow anonymizes the {@code users} row rather
     *  than deleting it, the same trap documented on {@code FeatureViewCountRepository}. Needs its
     *  own explicit hard-delete call. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM UserActivityDay a WHERE a.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
