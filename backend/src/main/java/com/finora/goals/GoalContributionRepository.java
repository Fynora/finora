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
     *  through goals rather than needing a caller to first fetch the user's goal ids.
     *
     *  <p>Native, not JPQL, on purpose: Goal carries {@code @SQLRestriction("deleted_at IS
     *  NULL")}, which Hibernate applies to a JPQL join through the entity just as much as a
     *  direct query against it (see {@code GoalRepository.findByUserIdIncludingDeleted}'s own
     *  doc comment for the same bypass, same reasoning). A user's momentum/Wrapped count for a
     *  month they contributed in, then later deleted that goal (to fix a mistaken name, replace
     *  it with a better one, whatever), must not silently drop that month's activity -- same
     *  "a behavioral fact survives cleanup" principle {@code countDistinctUsersEverActivated}
     *  and the old FinancialJourneyService both already establish elsewhere in this codebase. A
     *  plain JPQL join here would have filtered exactly that out. */
    @Query(value = "SELECT gc.* FROM goal_contributions gc JOIN goals g ON gc.goal_id = g.id WHERE g.user_id = :userId",
           nativeQuery = true)
    List<GoalContribution> findByUserId(@Param("userId") UUID userId);
}
