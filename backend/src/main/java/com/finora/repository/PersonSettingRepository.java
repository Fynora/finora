package com.finora.repository;

import com.finora.entity.PersonSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Per-person settings (V279). */
public interface PersonSettingRepository extends JpaRepository<PersonSetting, UUID> {
    List<PersonSetting> findByUserId(UUID userId);

    java.util.Optional<PersonSetting> findByUserIdAndPersonKey(UUID userId, String personKey);

    /** Set the family switch -- one statement, so two taps at once cannot both insert. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
           INSERT INTO person_settings (id, user_id, person_key, family)
           VALUES (gen_random_uuid(), :userId, :personKey, :family)
           ON CONFLICT (user_id, person_key) DO UPDATE SET family = EXCLUDED.family
           """, nativeQuery = true)
    int upsert(@Param("userId") UUID userId, @Param("personKey") String personKey, @Param("family") boolean family);

    /** AccountPurgeSweepService, through PeopleRepository: the users row is anonymized, not deleted,
     *  so the ON DELETE CASCADE never fires there. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PersonSetting x WHERE x.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
