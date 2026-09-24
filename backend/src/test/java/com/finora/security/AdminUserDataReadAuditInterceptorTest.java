package com.finora.security;

import com.finora.service.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AdminUserDataReadAuditInterceptorTest {

    private final UUID adminId = UUID.randomUUID();
    private final UUID targetUserId = UUID.randomUUID();
    private AuditService auditService;
    private AdminUserDataReadAuditInterceptor interceptor;

    @BeforeEach
    void setUp() {
        auditService = mock(AuditService.class);
        interceptor = new AdminUserDataReadAuditInterceptor(auditService, new CurrentUser());
        var principal = User.withUsername(adminId.toString()).password("x").authorities(List.of()).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest requestFor(String method, String pattern, String userIdValue) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, pattern.replace("{userId}", userIdValue));
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("userId", userIdValue));
        return request;
    }

    private MockHttpServletResponse responseWith(int status) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        return response;
    }

    @Test
    void aSuccessfulGetOfOneUsersDataIsRecordedAgainstTheAdminWithTheTargetAndThePattern() {
        String pattern = "/api/v1/admin/users/{userId}/transactions";

        interceptor.afterCompletion(requestFor("GET", pattern, targetUserId.toString()), responseWith(200), new Object(), null);

        verify(auditService).record(eq(adminId), eq(AdminUserDataReadAuditInterceptor.ACTION), eq("User"),
                eq(targetUserId), argThat(metadata -> pattern.equals(metadata.get("endpoint"))
                        && Integer.valueOf(200).equals(metadata.get("status"))));
    }

    @Test
    void theUserDetailRoute_whoseVariableIsNamedId_isRecordedToo() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/users/" + targetUserId);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/admin/users/{id}");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", targetUserId.toString()));

        interceptor.afterCompletion(request, responseWith(200), new Object(), null);

        verify(auditService).record(eq(adminId), eq(AdminUserDataReadAuditInterceptor.ACTION), eq("User"),
                eq(targetUserId), any());
    }

    @Test
    void aMutationIsNotDoubleRecorded_theServiceThatPerformsItAlreadyAuditsIt() {
        interceptor.afterCompletion(requestFor("DELETE", "/api/v1/admin/users/{userId}/transactions/{id}",
                targetUserId.toString()), responseWith(200), new Object(), null);

        verifyNoInteractions(auditService);
    }

    @Test
    void aRefusedOrMissingReadIsNotRecorded_nothingWasDisclosed() {
        interceptor.afterCompletion(requestFor("GET", "/api/v1/admin/users/{userId}/workspace",
                targetUserId.toString()), responseWith(403), new Object(), null);
        interceptor.afterCompletion(requestFor("GET", "/api/v1/admin/users/{userId}/workspace",
                targetUserId.toString()), responseWith(404), new Object(), null);

        verifyNoInteractions(auditService);
    }

    @Test
    void aRouteWhoseUserIdIsNotAUuidIsIgnoredRatherThanThrown() {
        interceptor.afterCompletion(requestFor("GET", "/api/v1/admin/users/{userId}", "not-a-uuid"),
                responseWith(200), new Object(), null);

        verifyNoInteractions(auditService);
    }

    @Test
    void anAuditWriteFailureNeverPropagatesIntoTheAlreadyServedResponse() {
        doThrow(new RuntimeException("db down")).when(auditService).record(any(), any(), any(), any(), any());

        MockHttpServletResponse response = responseWith(200);
        interceptor.afterCompletion(requestFor("GET", "/api/v1/admin/users/{userId}", targetUserId.toString()),
                response, new Object(), null);

        assertThat(response.getStatus()).isEqualTo(200);
    }
}
