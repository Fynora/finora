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

    /** AccountPurgeSweepService, through PeopleRepository: the users row is anonymized, not deleted,
     *  so the ON DELETE CASCADE never fires there. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PersonSetting x WHERE x.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
