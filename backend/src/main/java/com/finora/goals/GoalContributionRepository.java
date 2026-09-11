package com.finora.goals;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface GoalContributionRepository extends JpaRepository<GoalContribution, UUID> {
    List<GoalContribution> findByGoalIdOrderByContributedAtDesc(UUID goalId);

    /** DataExportService.buildBundle -- one batched query for every contribution across all of a
     *  user's goals (including soft-deleted ones, per goalIds' own source), not one
     *  findByGoalIdOrderByContributedAtDesc call per goal. */
    List<GoalContribution> findByGoalIdInOrderByContributedAtDesc(List<UUID> goalIds);

    /** GoalMomentumService's "N of last M months" computation and WrappedService's per-year
     *  contribution count. goal_contributions has no user_id column of its own, so this joins
     *  through goals rather than needing a caller to first fetch the user's goal ids. */
    @Query("SELECT gc FROM GoalContribution gc, Goal g WHERE gc.goalId = g.id AND g.userId = :userId")
    List<GoalContribution> findByUserId(@Param("userId") UUID userId);
}
