package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.NetWorthSnapshot;
import com.finora.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NetWorthSnapshotRepository#findFirstByUserIdAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc}
 * against real Postgres -- DashboardRangeService's balance-as-of-a-date lookup. Worth pinning
 * against a real DB specifically for the "at or before, nearest, not exact" semantics: a mock
 * would happily return whatever a test told it to and prove nothing about whether Spring Data
 * actually derived the right {@code <=} + {@code ORDER BY ... DESC} + {@code LIMIT 1} query from
 * the method name.
 */
class NetWorthSnapshotRepositoryIT extends AbstractIntegrationTest {

    @Autowired private NetWorthSnapshotRepository snapshotRepository;
    @Autowired private UserRepository userRepository;

    private UUID newUser() {
        User user = new User();
        user.setEmail("networth-asof-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Net Worth As-Of IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user).getId();
    }

    private void snapshot(UUID userId, LocalDate date, String netWorth) {
        NetWorthSnapshot s = new NetWorthSnapshot();
        s.setUserId(userId);
        s.setSnapshotDate(date);
        s.setTotalAssets(new BigDecimal(netWorth));
        s.setTotalLiabilities(BigDecimal.ZERO);
        s.setNetWorth(new BigDecimal(netWorth));
        snapshotRepository.save(s);
    }

    @Test
    @Transactional
    void findsTheNearestSnapshotAtOrBeforeTheGivenDate_notAnExactMatch() {
        UUID userId = newUser();
        snapshot(userId, LocalDate.of(2026, 6, 1), "10000");
        snapshot(userId, LocalDate.of(2026, 8, 15), "25000");
        // Deliberately no snapshot on 2026-08-31 itself -- the query must fall back to the nearest
        // one before it, not return empty just because there's no exact-date row.
        var result = snapshotRepository.findFirstByUserIdAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(
                userId, LocalDate.of(2026, 8, 31));

        assertThat(result).isPresent();
        assertThat(result.get().getSnapshotDate()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(result.get().getNetWorth()).isEqualByComparingTo("25000");
    }

    @Test
    @Transactional
    void returnsEmpty_whenEveryExistingSnapshotIsAfterTheGivenDate() {
        UUID userId = newUser();
        snapshot(userId, LocalDate.of(2026, 6, 1), "10000");

        var result = snapshotRepository.findFirstByUserIdAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(
                userId, LocalDate.of(2026, 1, 1));

        assertThat(result).isEmpty();
    }

    @Test
    @Transactional
    void scopesToTheGivenUser_notAnotherUsersSnapshotOnTheSameDate() {
        UUID userId = newUser();
        UUID otherUserId = newUser();
        snapshot(otherUserId, LocalDate.of(2026, 6, 1), "999999");

        var result = snapshotRepository.findFirstByUserIdAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(
                userId, LocalDate.of(2026, 8, 31));

        assertThat(result).isEmpty();
    }
}
