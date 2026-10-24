package com.finora.observability;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserRequestLoadInterceptorTest {

    private SimpleMeterRegistry registry;
    private UserRequestLoad load;
    private UserRequestLoadInterceptor interceptor;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        load = new UserRequestLoad(registry, Clock.systemUTC(), true);
        interceptor = new UserRequestLoadInterceptor(load);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void signInAs(String username, String... authorities) {
        var granted = java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        var principal = User.withUsername(username).password("x").authorities(granted).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private boolean request(MockHttpServletRequest request) {
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    private boolean request() {
        return request(new MockHttpServletRequest("GET", "/api/v1/dashboard"));
    }

    private DistributionSummary perMinute(String portal) {
        return registry.find("finora.user_load.requests_per_minute").tag("portal", portal).summary();
    }

    @Test
    void countsEachRequestOfASignedInCustomerAndNeverRefusesOne() {
        signInAs(UUID.randomUUID().toString(), "PORTAL_USER", "ROLE_USER");

        assertThat(request()).isTrue();
        assertThat(request()).isTrue();
        assertThat(request()).isTrue();
        load.flushNow();

        assertThat(perMinute("user").max()).isEqualTo(3.0);
    }

    @Test
    void anAdminPortalAccountIsMeasuredUnderAdmin() {
        signInAs(UUID.randomUUID().toString(), "PORTAL_ADMIN", "ROLE_ADMIN");

        request();
        load.flushNow();

        assertThat(perMinute("admin").max()).isEqualTo(1.0);
        assertThat(perMinute("user")).isNull();
    }

    @Test
    void anAccountHoldingBothPortalsIsMeasuredAsACustomer() {
        signInAs(UUID.randomUUID().toString(), "PORTAL_ADMIN", "PORTAL_USER");

        request();
        load.flushNow();

        assertThat(perMinute("user")).isNotNull();
        assertThat(perMinute("admin")).isNull();
    }

    @Test
    void anonymousRequestsAreNotCountedAndStillPass() {
        assertThat(request()).isTrue();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymousUser", null,
                        java.util.List.of(new SimpleGrantedAuthority("PORTAL_USER"))));
        assertThat(request()).isTrue();
        load.flushNow();

        assertThat(registry.find("finora.user_load.requests_per_minute").summary()).isNull();
    }

    @Test
    void aPrincipalWhoseNameIsNotAUserIdIsSkippedWithoutFailingTheRequest() {
        signInAs("someone@example.com", "PORTAL_USER");

        assertThat(request()).isTrue();
        load.flushNow();

        assertThat(registry.find("finora.user_load.requests_per_minute").summary()).isNull();
    }

    @Test
    void anAccountWithNoPortalAuthorityIsNotCounted() {
        signInAs(UUID.randomUUID().toString());

        assertThat(request()).isTrue();
        load.flushNow();

        assertThat(registry.find("finora.user_load.requests_per_minute").summary()).isNull();
    }

    @Test
    void anErrorOrAsyncRedispatchOfTheSameRequestIsNotCountedAgain() {
        signInAs(UUID.randomUUID().toString(), "PORTAL_USER");
        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/api/v1/dashboard");
        MockHttpServletRequest redispatch = new MockHttpServletRequest("GET", "/api/v1/dashboard");
        redispatch.setDispatcherType(DispatcherType.ASYNC);
        MockHttpServletRequest error = new MockHttpServletRequest("GET", "/error");
        error.setDispatcherType(DispatcherType.ERROR);

        request(first);
        request(redispatch);
        request(error);
        load.flushNow();

        assertThat(perMinute("user").max()).isEqualTo(1.0);
    }

    @Test
    void theMatchedRouteTemplateIsPassedOnButNeverTheIdInThePath() {
        signInAs(UUID.randomUUID().toString(), "PORTAL_USER");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/transactions/" + UUID.randomUUID());
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/transactions/{id}");

        assertThat(request(request)).isTrue();
        load.flushNow();

        assertThat(perMinute("user").max()).isEqualTo(1.0);
    }

    @Test
    void aFailureInsideTheRecorderNeverFailsTheRequest() {
        UserRequestLoad broken = new UserRequestLoad(registry, Clock.systemUTC(), true) {
            @Override
            public void record(UUID userId, String portal, String route) {
                throw new IllegalStateException("boom");
            }
        };
        signInAs(UUID.randomUUID().toString(), "PORTAL_USER");

        assertThat(new UserRequestLoadInterceptor(broken)
                .preHandle(new MockHttpServletRequest("GET", "/api/v1/dashboard"),
                        new MockHttpServletResponse(), new Object())).isTrue();
    }
}
