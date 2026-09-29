package com.finora.repository;

import com.finora.entity.LayoutProfile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface LayoutProfileRepository extends JpaRepository<LayoutProfile, UUID> {

    boolean existsByNameIgnoreCase(String name);

    /** Locks the profile row, so two operators adding a layout to the same profile at once are
     *  handed consecutive versions instead of racing to the same one. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from LayoutProfile p where p.id = :id")
    Optional<LayoutProfile> findByIdForUpdate(@Param("id") UUID id);
}
