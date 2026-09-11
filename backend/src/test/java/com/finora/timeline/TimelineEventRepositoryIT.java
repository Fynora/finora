package com.finora.timeline;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Postgres, not a mock -- insertIfNew's correctness depends on the two partial unique
 *  indexes from V193 actually being enforced by the database, which a mocked repository can't
 *  exercise. See AbstractIntegrationTest's own class doc for why *IT extends it instead of
 *  using @DataJpaTest (not used anywhere in this codebase). */
class TimelineEventRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private TimelineEventRepository repository;

    @Test
    @Transactional
    void insertIfNew_blocksASecondSingletonEvent_forTheSameUserAndType() {
        UUID userId = UUID.randomUUID();
        repository.insertIfNew(userId, "FIRST_GOAL_CREATED", "STARTING", "LANDMARK", true,
                null, "Started your first goal", null, Instant.now());
        repository.insertIfNew(userId, "FIRST_GOAL_CREATED", "STARTING", "LANDMARK", true,
                null, "Started your first goal", null, Instant.now());

        assertThat(repository.findByUserIdOrderByOccurredAtDesc(userId)).hasSize(1);
    }

    @Test
    @Transactional
    void insertIfNew_allowsTheSameEventType_forTwoDifferentReferenceIds() {
        UUID userId = UUID.randomUUID();
        UUID goalA = UUID.randomUUID();
        UUID goalB = UUID.randomUUID();
        repository.insertIfNew(userId, "GOAL_COMPLETED", "TRANSFORMATION", "LANDMARK", true,
                goalA, "Completed a goal", null, Instant.now());
        repository.insertIfNew(userId, "GOAL_COMPLETED", "TRANSFORMATION", "LANDMARK", true,
                goalB, "Completed a goal", null, Instant.now());

        assertThat(repository.findByUserIdOrderByOccurredAtDesc(userId)).hasSize(2);
    }

    @Test
    @Transactional
    void insertIfNew_blocksASecondEvent_forTheSameUserTypeAndReferenceId() {
        UUID userId = UUID.randomUUID();
        UUID goalId = UUID.randomUUID();
        repository.insertIfNew(userId, "GOAL_COMPLETED", "TRANSFORMATION", "LANDMARK", true,
                goalId, "Completed a goal", null, Instant.now());
        repository.insertIfNew(userId, "GOAL_COMPLETED", "TRANSFORMATION", "LANDMARK", true,
                goalId, "Completed a goal", null, Instant.now());

        assertThat(repository.findByUserIdOrderByOccurredAtDesc(userId)).hasSize(1);
    }
}
