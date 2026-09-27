package com.finora.repository;

import com.finora.entity.InflowKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface InflowKindRepository extends JpaRepository<InflowKind, UUID> {

    List<InflowKind> findByUserId(UUID userId);

    boolean existsByUserIdAndNameIgnoreCase(UUID userId, String name);

    boolean existsByUserIdAndNameIgnoreCaseAndIdNot(UUID userId, String name, UUID id);

    long countByUserIdAndBuiltInIsNotNull(UUID userId);

    /** ON CONFLICT DO NOTHING covers both unique indexes, so parallel first requests are safe. */
    @Modifying
    @Query(value = """
            INSERT INTO inflow_kinds (id, user_id, name, counts_as_income, built_in)
            VALUES (gen_random_uuid(), :userId, :name, :countsAsIncome, :builtIn)
            ON CONFLICT DO NOTHING""", nativeQuery = true)
    int insertBuiltInIfMissing(@Param("userId") UUID userId, @Param("name") String name,
                               @Param("countsAsIncome") boolean countsAsIncome, @Param("builtIn") String builtIn);

    @Modifying
    @Query("DELETE FROM InflowKind k WHERE k.userId = :userId")
    int hardDeleteByUserId(@Param("userId") UUID userId);
}
