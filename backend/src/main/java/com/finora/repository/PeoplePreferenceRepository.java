package com.finora.repository;

import com.finora.entity.PeoplePreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** The user's own UPI id (V279), one row per user. */
public interface PeoplePreferenceRepository extends JpaRepository<PeoplePreference, UUID> {

    /** AccountPurgeSweepService, through PeopleRepository: the users row is anonymized, not deleted,
     *  so the ON DELETE CASCADE never fires there. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PeoplePreference x WHERE x.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
