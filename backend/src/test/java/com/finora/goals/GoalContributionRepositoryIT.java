package com.finora.goals;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link GoalContributionRepository#findByUserId} against a real Postgres, not a mock --
 *  proving it survives the goal being soft-deleted afterward is exactly the behavior a mocked
 *  repository can't demonstrate (Goal's @SQLRestriction is a real database predicate). See that
 *  method's own doc comment for why a JPQL join would have filtered this out. */
class GoalContributionRepositoryIT extends AbstractIntegrationTest {

    @Autowired private GoalRepository goalRepository;
    @Autowired private GoalContributionRepository contributionRepository;
    @Autowired private UserRepository userRepository;

    private UUID newUser() {
        User user = new User();
        user.setEmail("goal-contribution-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Goal Contribution IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user).getId();
    }

    private Goal newGoal(UUID userId) {
        Goal g = new Goal();
        g.setUserId(userId);
        g.setName("Trip");
        g.setTargetAmount(new BigDecimal("10000"));
        g.setCurrentAmount(BigDecimal.ZERO);
        return goalRepository.save(g);
    }

    @Test
    @Transactional
    void findByUserId_stillReturnsAContribution_afterItsGoalIsSoftDeleted() {
        UUID userId = newUser();
        Goal goal = newGoal(userId);
        GoalContribution gc = new GoalContribution();
        gc.setGoalId(goal.getId());
        gc.setAmount(new BigDecimal("500"));
        gc.setContributedAt(LocalDate.of(2026, 6, 1));
        contributionRepository.save(gc);

        goalRepository.delete(goal); // soft-delete: sets deleted_at, per Goal's @SQLDelete

        List<GoalContribution> result = contributionRepository.findByUserId(userId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAmount()).isEqualByComparingTo("500");
    }

    @Test
    @Transactional
    void findByUserId_returnsNothing_forAnotherUsersContributions() {
        UUID userId = newUser();
        UUID otherUserId = newUser();
        Goal otherGoal = newGoal(otherUserId);
        GoalContribution gc = new GoalContribution();
        gc.setGoalId(otherGoal.getId());
        gc.setAmount(new BigDecimal("500"));
        gc.setContributedAt(LocalDate.of(2026, 6, 1));
        contributionRepository.save(gc);

        assertThat(contributionRepository.findByUserId(userId)).isEmpty();
    }
}
