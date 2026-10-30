package com.finora.service;

import com.finora.repository.UserRepository;
import com.finora.service.PhoneVerificationProvider.PhoneIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** All numbers are synthetic (+91 followed by a run that names its role). */
class PhoneIdentitySweepServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final Instant TWO_DAYS_AGO = NOW.minus(Duration.ofDays(2));
    private static final String LIVE = "+911111111111"; // synthetic-ok
    private static final String ORPHAN = "+912222222222"; // synthetic-ok
    private static final String OTHER_ORPHAN = "+913333333333"; // synthetic-ok

    private PhoneVerificationProvider provider;
    private UserRepository userRepository;
    private PhoneIdentitySweepService service;

    @BeforeEach
    void setUp() {
        provider = mock(PhoneVerificationProvider.class);
        when(provider.isConfigured()).thenReturn(true);
        when(provider.deleteIdentityForPhoneNumber(anyString())).thenReturn(true);
        userRepository = mock(UserRepository.class);
        when(userRepository.listContactNumbersInBothPortals()).thenReturn(List.of(LIVE));
        service = new PhoneIdentitySweepService(provider, userRepository);
    }

    @Test
    void deletesARecordWhoseNumberIsOnNoAccount() {
        when(provider.listPhoneIdentities()).thenReturn(List.of(
                new PhoneIdentity(LIVE, TWO_DAYS_AGO), new PhoneIdentity(ORPHAN, TWO_DAYS_AGO)));

        PhoneIdentitySweepService.Result result = service.sweep(NOW);

        verify(provider).deleteIdentityForPhoneNumber(ORPHAN);
        verify(provider, never()).deleteIdentityForPhoneNumber(LIVE);
        assertThat(result).isEqualTo(new PhoneIdentitySweepService.Result(1, 0, 1));
    }

    /** A number stored before E.164 normalisation still counts as the account's. */
    @Test
    void keepsARecordWhoseNumberAnAccountStoresInALegacyForm() {
        when(userRepository.listContactNumbersInBothPortals()).thenReturn(List.of("22222 22222")); // synthetic-ok
        when(provider.listPhoneIdentities()).thenReturn(List.of(new PhoneIdentity(ORPHAN, TWO_DAYS_AGO)));

        service.sweep(NOW);

        verify(provider, never()).deleteIdentityForPhoneNumber(any());
    }

    /** A verification still in progress (a phone change not yet completed) is left alone. */
    @Test
    void keepsARecordActiveWithinTheLast24Hours() {
        when(provider.listPhoneIdentities()).thenReturn(List.of(
                new PhoneIdentity(ORPHAN, NOW.minus(Duration.ofHours(23)))));

        service.sweep(NOW);

        verify(provider, never()).deleteIdentityForPhoneNumber(any());
    }

    @Test
    void keepsARecordOneSecondInsideTheGrace() {
        when(provider.listPhoneIdentities()).thenReturn(List.of(
                new PhoneIdentity(ORPHAN, NOW.minus(PhoneIdentitySweepService.GRACE).plusSeconds(1))));

        service.sweep(NOW);

        verify(provider, never()).deleteIdentityForPhoneNumber(any());
    }

    /** Exactly 24 hours old is past the grace: "within the last 24 hours" is strict. */
    @Test
    void deletesARecordExactlyAtTheGrace() {
        when(provider.listPhoneIdentities()).thenReturn(List.of(
                new PhoneIdentity(ORPHAN, NOW.minus(PhoneIdentitySweepService.GRACE))));

        service.sweep(NOW);

        verify(provider).deleteIdentityForPhoneNumber(ORPHAN);
    }

    /** No account has a number: far likelier a broken read than the truth, so nothing is deleted. */
    @Test
    void doesNothingWhenNoAccountHasANumber() {
        when(userRepository.listContactNumbersInBothPortals()).thenReturn(List.of());
        when(provider.listPhoneIdentities()).thenReturn(List.of(new PhoneIdentity(ORPHAN, TWO_DAYS_AGO)));

        PhoneIdentitySweepService.Result result = service.sweep(NOW);

        verify(provider, never()).listPhoneIdentities();
        verify(provider, never()).deleteIdentityForPhoneNumber(any());
        assertThat(result).isEqualTo(new PhoneIdentitySweepService.Result(0, 0, 0));
    }

    @Test
    void doesNothingWhenTheProviderIsNotConfigured() {
        when(provider.isConfigured()).thenReturn(false);

        service.sweep(NOW);

        verify(provider, never()).listPhoneIdentities();
        verify(userRepository, never()).listContactNumbersInBothPortals();
    }

    @Test
    void oneFailedDeleteDoesNotStopTheRest() {
        when(provider.listPhoneIdentities()).thenReturn(List.of(
                new PhoneIdentity(ORPHAN, TWO_DAYS_AGO), new PhoneIdentity(OTHER_ORPHAN, TWO_DAYS_AGO)));
        when(provider.deleteIdentityForPhoneNumber(ORPHAN)).thenThrow(new IllegalStateException("provider unavailable"));

        PhoneIdentitySweepService.Result result = service.sweep(NOW);

        verify(provider).deleteIdentityForPhoneNumber(OTHER_ORPHAN);
        assertThat(result).isEqualTo(new PhoneIdentitySweepService.Result(1, 1, 0));
    }

    @Test
    void theScheduledRunDoesNothingWhenSwitchedOff() {
        ReflectionTestUtils.setField(service, "enabled", false);

        service.scheduledSweep();

        verifyNoInteractions(userRepository);
        verify(provider, never()).listPhoneIdentities();
    }

    /** A provider that cannot be read ends the run with a log line; it never reaches the scheduler. */
    @Test
    void theScheduledRunSurvivesAProviderThatCannotBeRead() {
        ReflectionTestUtils.setField(service, "enabled", true);
        when(provider.listPhoneIdentities()).thenThrow(new IllegalStateException("provider unavailable"));

        service.scheduledSweep();

        verify(provider, never()).deleteIdentityForPhoneNumber(any());
    }
}
