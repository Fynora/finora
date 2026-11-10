package com.finora.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;

class SubmissionLockTest {

    private final UserRepository users = mock(UserRepository.class);
    private final UUID userId = UUID.randomUUID();

    @Test
    void setsTheWaitLimitBeforeTakingTheLock_andReturnsTheLockedUser() {
        User user = new User();
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(user));

        assertThat(SubmissionLock.acquire(users, userId)).containsSame(user);

        InOrder order = inOrder(users);
        order.verify(users).setLocalLockTimeout("5s");
        order.verify(users).findByIdForUpdate(userId);
    }

    @Test
    void aLockNotGrantedInTime_becomesATryAgainConflict_notA500() {
        when(users.findByIdForUpdate(userId)).thenThrow(new CannotAcquireLockException("lock timeout"));

        assertThatThrownBy(() -> SubmissionLock.acquire(users, userId))
                .isInstanceOf(ApiException.class)
                .hasMessage(SubmissionLock.BUSY_MESSAGE)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(SubmissionLock.BUSY_MESSAGE).doesNotContainIgnoringCase("refresh");
    }
}
