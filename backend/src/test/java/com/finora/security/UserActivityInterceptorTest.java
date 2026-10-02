package com.finora.security;

import com.finora.repository.UserActivityDayRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class UserActivityInterceptorTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final UUID userId = UUID.randomUUID();
    private UserActivityDayRepository repository;
    private MutableClock clock;
    private UserActivityInterceptor interceptor;

    @BeforeEach
    void setUp() {
        repository = mock(UserActivityDayRepository.class);
        // 2026-10-01 10:00 IST
        clock = new MutableClock(Instant.parse("2026-10-01T04:30:00Z"), IST);
        interceptor = new UserActivityInterceptor(repository, clock);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void signInAs(UUID id, String... authorities) {
        var granted = java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        var principal = User.withUsername(id.toString()).password("x").authorities(granted).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private boolean request() {
        return interceptor.preHandle(new MockHttpServletRequest("GET", "/api/v1/dashboard"),
                new MockHttpServletResponse(), new Object());
    }

    @Test
    void recordsAUserPortalAccountOncePerDay() {
        signInAs(userId, "PORTAL_USER", "ROLE_USER");

        assertThat(request()).isTrue();
        assertThat(request()).isTrue();
        assertThat(request()).isTrue();

        verify(repository, times(1)).recordDay(userId, LocalDate.of(2026, 10, 1));
    }

    @Test
    void recordsAgainOnTheNextDay() {
        signInAs(userId, "PORTAL_USER");

        request();
        clock.set(Instant.parse("2026-10-02T04:30:00Z"));
        request();
        request();

        verify(repository, times(1)).recordDay(userId, LocalDate.of(2026, 10, 1));
        verify(repository, times(1)).recordDay(userId, LocalDate.of(2026, 10, 2));
    }

    @Test
    void theDayIsTheReportingZoneDayNotTheUtcDay() {
        // 2026-09-30 19:00 UTC is already 2026-10-01 00:30 in India.
        clock.set(Instant.parse("2026-09-30T19:00:00Z"));
        signInAs(userId, "PORTAL_USER");

        request();

        verify(repository).recordDay(userId, LocalDate.of(2026, 10, 1));
    }

    @Test
    void justBeforeIndianMidnightIsStillThePreviousDay() {
        // 2026-09-30 18:29:59 UTC is 2026-09-30 23:59:59 IST.
        clock.set(Instant.parse("2026-09-30T18:29:59Z"));
        signInAs(userId, "PORTAL_USER");

        request();

        verify(repository).recordDay(userId, LocalDate.of(2026, 9, 30));
    }

    @Test
    void eachUserIsRecordedSeparately() {
        UUID other = UUID.randomUUID();

        signInAs(userId, "PORTAL_USER");
        request();
        signInAs(other, "PORTAL_USER");
        request();

        verify(repository).recordDay(userId, LocalDate.of(2026, 10, 1));
        verify(repository).recordDay(other, LocalDate.of(2026, 10, 1));
    }

    @Test
    void adminPortalAccountsAreNotCounted() {
        signInAs(userId, "PORTAL_ADMIN", "ROLE_ADMIN");

        assertThat(request()).isTrue();

        verifyNoInteractions(repository);
    }

    @Test
    void anAccountWithNoPortalAuthorityIsNotCounted() {
        signInAs(userId);

        request();

        verifyNoInteractions(repository);
    }

    @Test
    void anonymousRequestsAreNotCounted() {
        assertThat(request()).isTrue();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymousUser", null,
                        List.of(new SimpleGrantedAuthority("PORTAL_USER"))));
        assertThat(request()).isTrue();

        verifyNoInteractions(repository);
    }

    @Test
    void aPrincipalWhoseUsernameIsNotAUserIdIsNotCounted() {
        var principal = User.withUsername("someone@example.com").password("x")
                .authorities("PORTAL_USER").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        assertThat(request()).isTrue();

        verifyNoInteractions(repository);
    }

    @Test
    void aFailedWriteNeverFailsTheRequestAndIsRetriedOnTheNextOne() {
        signInAs(userId, "PORTAL_USER");
        doThrow(new RuntimeException("database down")).when(repository).recordDay(any(), any());

        assertThat(request()).isTrue();
        assertThat(request()).isTrue();

        // Not cached as recorded after a failure -- both requests attempted the write.
        verify(repository, times(2)).recordDay(userId, LocalDate.of(2026, 10, 1));
    }

    /** A clock the test can move forward, to cross a day boundary on the same interceptor. */
    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void set(Instant instant) { this.instant = instant; }

        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new MutableClock(instant, zone); }
        @Override public Instant instant() { return instant; }
    }
}
