package com.finora.repository;

import com.finora.entity.PeoplePreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** The user's own UPI id (V281), one row per user. */
public interface PeoplePreferenceRepository extends JpaRepository<PeoplePreference, UUID> {

    /** Set the user's own UPI id -- one statement, so two saves at once cannot both insert. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
           INSERT INTO people_preferences (user_id, own_upi_id, updated_at)
           VALUES (:userId, :ownUpiId, now())
           ON CONFLICT (user_id) DO UPDATE SET own_upi_id = EXCLUDED.own_upi_id, updated_at = now()
           """, nativeQuery = true)
    int upsert(@Param("userId") UUID userId, @Param("ownUpiId") String ownUpiId);

    /** AccountPurgeSweepService, through PeopleRepository: the users row is anonymized, not deleted,
     *  so the ON DELETE CASCADE never fires there. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PeoplePreference x WHERE x.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
