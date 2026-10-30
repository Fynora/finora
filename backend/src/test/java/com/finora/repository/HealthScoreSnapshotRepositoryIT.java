package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.HealthScoreSnapshot;
import com.finora.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HealthScoreSnapshotRepositoryIT extends AbstractIntegrationTest {

    @Autowired private HealthScoreSnapshotRepository repository;
    @Autowired private UserRepository userRepository;

    private UUID persistUser() {
        User u = new User();
        u.setEmail("health-score-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("irrelevant-for-this-test");
        u.setFullName("Health Score Test");
        return userRepository.save(u).getId();
    }

    @Test
    void upsertInsertsThenUpdatesTheSameMonthRow() {
        UUID userId = persistUser();

        repository.upsertForMonth(userId, "2026-09", 51, "Fair", 0.0, 100.0, 2.0, 100.0, 100.0);
        List<HealthScoreSnapshot> afterFirst = repository.findTop6ByUserIdOrderByYearMonthDesc(userId);
        assertThat(afterFirst).hasSize(1);
        assertThat(afterFirst.get(0).getOverallScore()).isEqualTo(51);

        repository.upsertForMonth(userId, "2026-09", 60, "Good", 20.0, 100.0, 10.0, 100.0, 100.0);
        List<HealthScoreSnapshot> afterSecond = repository.findTop6ByUserIdOrderByYearMonthDesc(userId);
        assertThat(afterSecond).hasSize(1); // still one row -- updated, not duplicated
        assertThat(afterSecond.get(0).getOverallScore()).isEqualTo(60);
        assertThat(afterSecond.get(0).getLabel()).isEqualTo("Good");
    }

    @Test
    void pastMonthsAreUntouchedByALaterMonthsUpsert() {
        UUID userId = persistUser();

        repository.upsertForMonth(userId, "2026-07", 40, "Fair", 0.0, 80.0, 0.0, 80.0, 80.0);
        repository.upsertForMonth(userId, "2026-08", 45, "Fair", 5.0, 80.0, 5.0, 80.0, 80.0);
        repository.upsertForMonth(userId, "2026-09", 51, "Fair", 0.0, 100.0, 2.0, 100.0, 100.0);

        List<HealthScoreSnapshot> all = repository.findTop6ByUserIdOrderByYearMonthDesc(userId);
        assertThat(all).extracting(HealthScoreSnapshot::getYearMonth)
                .containsExactly("2026-09", "2026-08", "2026-07"); // newest first
        assertThat(all).extracting(HealthScoreSnapshot::getOverallScore)
                .containsExactly(51, 45, 40); // each month's own value, untouched by later upserts
    }

    @Test
    void findsMostRecentPriorSnapshotStrictlyBeforeGivenMonth() {
        UUID userId = persistUser();
        repository.upsertForMonth(userId, "2026-06", 30, "Needs Attention", 0.0, 60.0, 0.0, 60.0, 60.0);
        repository.upsertForMonth(userId, "2026-08", 45, "Fair", 5.0, 80.0, 5.0, 80.0, 80.0);

        Optional<HealthScoreSnapshot> prior =
                repository.findFirstByUserIdAndYearMonthLessThanOrderByYearMonthDesc(userId, "2026-09");

        assertThat(prior).isPresent();
        assertThat(prior.get().getYearMonth()).isEqualTo("2026-08"); // most recent before 2026-09, gap at 07 is fine
    }

    @Test
    void findsNoPriorSnapshotWhenNoneExists() {
        UUID userId = persistUser();
        Optional<HealthScoreSnapshot> prior =
                repository.findFirstByUserIdAndYearMonthLessThanOrderByYearMonthDesc(userId, "2026-09");
        assertThat(prior).isEmpty();
    }

    // V296: a part that could not be measured is stored as null, not as a made-up score.
    @Test
    void aPartThatCouldNotBeMeasuredIsStoredAsNull() {
        UUID userId = persistUser();

        repository.upsertForMonth(userId, "2026-10", 56, "Fair", null, 100.0, 76.0, null, null);

        HealthScoreSnapshot saved = repository.findTop6ByUserIdOrderByYearMonthDesc(userId).get(0);
        assertThat(saved.getSavingsRateScore()).isNull();
        assertThat(saved.getSpendConsistencyScore()).isNull();
        assertThat(saved.getCashFlowStabilityScore()).isNull();
        assertThat(saved.getDebtScore()).isEqualTo(100.0);
        assertThat(saved.getEmergencyFundScore()).isEqualTo(76.0);
    }
}
