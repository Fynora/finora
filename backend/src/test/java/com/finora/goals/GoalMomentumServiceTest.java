package com.finora.goals;

import com.finora.dto.GoalMomentumDto;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoalMomentumServiceTest {

    private GoalContributionRepository contributionRepository;
    private UserRepository userRepository;
    private GoalMomentumService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        contributionRepository = mock(GoalContributionRepository.class);
        userRepository = mock(UserRepository.class);
        service = new GoalMomentumService(contributionRepository, userRepository);
    }

    private GoalContribution contributionOn(LocalDate date) {
        GoalContribution gc = new GoalContribution();
        ReflectionTestUtils.setField(gc, "contributedAt", date);
        return gc;
    }

    @Test
    void compute_countsDistinctMonthsWithAtLeastOneContribution_inTheLast6Months() {
        LocalDate today = LocalDate.of(2026, 9, 15);
        when(contributionRepository.findByUserId(userId)).thenReturn(List.of(
                contributionOn(LocalDate.of(2026, 9, 1)),   // this month
                contributionOn(LocalDate.of(2026, 8, 10)),  // last month
                contributionOn(LocalDate.of(2026, 8, 20)),  // same month as above -- counts once
                contributionOn(LocalDate.of(2026, 3, 1))    // outside the 6-month window
        ));

        GoalMomentumDto result = service.compute(userId, today);

        assertThat(result.activeMonths()).isEqualTo(2);
        assertThat(result.windowMonths()).isEqualTo(6);
    }

    @Test
    void compute_returnsZero_whenTheUserHasNeverContributed() {
        when(contributionRepository.findByUserId(userId)).thenReturn(List.of());

        GoalMomentumDto result = service.compute(userId, LocalDate.of(2026, 9, 15));

        assertThat(result.activeMonths()).isEqualTo(0);
    }

    @Test
    void compute_includesTheWindowBoundaryMonth_exactly6MonthsAgo() {
        LocalDate today = LocalDate.of(2026, 9, 15);
        when(contributionRepository.findByUserId(userId)).thenReturn(List.of(
                contributionOn(LocalDate.of(2026, 4, 1)) // exactly 5 months before September -> April is the 6th month in the window (Apr-Sep)
        ));

        GoalMomentumDto result = service.compute(userId, today);

        assertThat(result.activeMonths()).isEqualTo(1);
    }

    // Bug fix: the controller used to pass a bare LocalDate.now() (server zone). This proves the
    // zone-aware overload actually resolves the user's own timezone rather than the JVM default.
    @Test
    void compute_withOnlyAUserId_resolvesTodayInTheUsersOwnTimezone_notTheServersDefault() {
        User user = new User();
        // UTC+14 -- as far ahead of UTC as any real IANA zone gets, deliberately chosen so its
        // "today" is essentially guaranteed to differ from the system default zone (almost
        // certainly UTC in CI), making this assertion meaningful rather than coincidentally
        // passing either way.
        user.setTimezone("Pacific/Kiritimati");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        LocalDate expectedToday = LocalDate.now(ZoneId.of("Pacific/Kiritimati"));
        when(contributionRepository.findByUserId(userId)).thenReturn(List.of(contributionOn(expectedToday)));

        GoalMomentumDto result = service.compute(userId);

        assertThat(result.activeMonths()).isEqualTo(1);
    }
}
