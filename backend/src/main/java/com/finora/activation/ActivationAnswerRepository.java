package com.finora.activation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ActivationAnswerRepository extends JpaRepository<ActivationAnswerEntry, UUID> {

    boolean existsByUserIdAndAnswerDate(UUID userId, LocalDate answerDate);

    boolean existsByUserIdAndMomentAndAnswerDateAfter(UUID userId, ActivationMoment moment, LocalDate after);

    List<ActivationAnswerEntry> findByUserIdOrderByAnswerDateAsc(UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ActivationAnswerEntry a WHERE a.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
