package com.finora.service;

import com.finora.repository.EmailLoginOtpRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailLoginOtpRetentionSweepServiceTest {

    private EmailLoginOtpRepository emailLoginOtpRepository;
    private EmailLoginOtpRetentionSweepService sweep;

    @BeforeEach
    void setUp() {
        emailLoginOtpRepository = mock(EmailLoginOtpRepository.class);
        sweep = new EmailLoginOtpRetentionSweepService(emailLoginOtpRepository);
    }

    @Test
    void sweep_deletesRowsOlderThanTwentyFourHours() {
        when(emailLoginOtpRepository.deleteByCreatedAtBefore(any())).thenReturn(3);

        sweep.sweep();

        var captor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(emailLoginOtpRepository).deleteByCreatedAtBefore(captor.capture());
        Instant expectedCutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        assertThat(captor.getValue()).isCloseTo(expectedCutoff, org.assertj.core.api.Assertions.within(5, ChronoUnit.SECONDS));
    }
}
