package com.finora.service;

import com.finora.dto.AdminDtos.SpendingTrackingMethodCount;
import com.finora.onboarding.SpendingTrackingMethod;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminSpendingTrackingServiceTest {

    private static UserRepository.SpendingTrackingCount row(String method, long count) {
        return new UserRepository.SpendingTrackingCount() {
            public String getMethod() { return method; }
            public long getCount() { return count; }
        };
    }

    @Test
    void listsEveryAnswerInOrder_withZeros_andCountsTheUnansweredApart() {
        UserRepository users = mock(UserRepository.class);
        when(users.countBySpendingTrackingMethod()).thenReturn(List.of(
                row("SPREADSHEET", 3), row(null, 5), row("IN_MY_HEAD", 7)));

        var breakdown = new AdminSpendingTrackingService(users).breakdown();

        assertThat(breakdown.answered()).isEqualTo(10);
        assertThat(breakdown.notAnswered()).isEqualTo(5);
        assertThat(breakdown.methods()).extracting(SpendingTrackingMethodCount::method)
                .containsExactlyElementsOf(Arrays.stream(SpendingTrackingMethod.values()).map(Enum::name).toList());
        assertThat(breakdown.methods()).filteredOn(m -> m.method().equals("SPREADSHEET")).singleElement()
                .extracting(SpendingTrackingMethodCount::count).isEqualTo(3L);
        assertThat(breakdown.methods()).filteredOn(m -> m.method().equals("PAPER")).singleElement()
                .extracting(SpendingTrackingMethodCount::count).isEqualTo(0L);
    }

    @Test
    void noAccountsAtAll_isAllZeros() {
        UserRepository users = mock(UserRepository.class);
        when(users.countBySpendingTrackingMethod()).thenReturn(List.of());

        var breakdown = new AdminSpendingTrackingService(users).breakdown();

        assertThat(breakdown.answered()).isZero();
        assertThat(breakdown.notAnswered()).isZero();
        assertThat(breakdown.methods()).allMatch(m -> m.count() == 0);
    }
}
