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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AdminUserDataReadAuditInterceptorTest {

    private final UUID adminId = UUID.randomUUID();
    private final UUID targetUserId = UUID.randomUUID();
    private AuditService auditService;
    private AdminReadOwnerResolver ownerResolver;
    private AdminUserDataReadAuditInterceptor interceptor;

    @BeforeEach
    void setUp() {
        auditService = mock(AuditService.class);
        // The real resolver for a user id (no database needed); other owners are stubbed per test,
        // and the SQL behind them is covered by AdminReadAuditIT.
        ownerResolver = mock(AdminReadOwnerResolver.class);
        when(ownerResolver.ownerOf(eq(AdminReadAuditPolicy.Owner.USER), any())).thenAnswer(inv -> {
            try {
                return UUID.fromString(inv.getArgument(1));
            } catch (IllegalArgumentException e) {
                return null;
            }
        });
        interceptor = new AdminUserDataReadAuditInterceptor(auditService, new CurrentUser(), ownerResolver);
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

    /** A read that succeeded is recorded even when its id names no one we can find: the row then
     *  carries the id the admin asked for and no owner, rather than nothing at all. */
    @Test
    void aRouteWhoseUserIdIsNotAUuidIsRecordedWithNoOwnerRatherThanThrown() {
        interceptor.afterCompletion(requestFor("GET", "/api/v1/admin/users/{userId}", "not-a-uuid"),
                responseWith(200), new Object(), null);

        verify(auditService).record(eq(adminId), eq(AdminUserDataReadAuditInterceptor.ACTION), eq("User"),
                isNull(), argThat(metadata -> "not-a-uuid".equals(metadata.get("userId"))));
    }

    private MockHttpServletRequest requestWithVariables(String pattern, Map<String, String> variables) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", pattern);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, variables);
        return request;
    }

    /** Privacy workstream 9: one person's data read under a path that is not /admin/users/{id}. */
    @Test
    void aHeldImportDetailIsRecordedAgainstTheImportsOwner_withTheJobId() {
        UUID jobId = UUID.randomUUID();
        when(ownerResolver.ownerOf(AdminReadAuditPolicy.Owner.IMPORT_JOB, jobId.toString())).thenReturn(targetUserId);

        interceptor.afterCompletion(requestWithVariables("/api/v1/admin/held-imports/{jobId}",
                Map.of("jobId", jobId.toString())), responseWith(200), new Object(), null);

        verify(auditService).record(eq(adminId), eq(AdminUserDataReadAuditInterceptor.ACTION), eq("User"),
                eq(targetUserId), argThat(metadata -> jobId.toString().equals(metadata.get("jobId"))
                        && "/api/v1/admin/held-imports/{jobId}".equals(metadata.get("endpoint"))));
    }

    @Test
    void aPageOfAStatementsOriginalIsRecordedAgainstItsSubject() {
        UUID viewId = UUID.randomUUID();
        when(ownerResolver.ownerOf(AdminReadAuditPolicy.Owner.ORIGINAL_VIEW, viewId.toString())).thenReturn(targetUserId);

        interceptor.afterCompletion(requestWithVariables("/api/v1/admin/original-views/{viewId}/pages/{pageNumber}",
                Map.of("viewId", viewId.toString(), "pageNumber", "2")), responseWith(200), new Object(), null);

        verify(auditService).record(eq(adminId), eq(AdminUserDataReadAuditInterceptor.ACTION), eq("User"),
                eq(targetUserId), any());
    }

    /** Looked up by email: the row names the customer by id and does not copy the email. */
    @Test
    void aLookupByEmailNamesTheCustomer_andDoesNotStoreTheEmail() {
        MockHttpServletRequest request = requestWithVariables("/api/v1/admin/imports/analyses/failures/by-user", Map.of());
        request.addParameter("email", "synthetic.customer@example.com");
        when(ownerResolver.ownerOf(AdminReadAuditPolicy.Owner.USER_EMAIL_PARAM, "synthetic.customer@example.com"))
                .thenReturn(targetUserId);

        interceptor.afterCompletion(request, responseWith(200), new Object(), null);

        verify(auditService).record(eq(adminId), eq(AdminUserDataReadAuditInterceptor.ACTION), eq("User"),
                eq(targetUserId), argThat(metadata -> !metadata.containsKey("email")
                        && !metadata.toString().contains("synthetic.customer")));
    }

    @Test
    void aListingOfCustomersIsRecordedOnce_withTheFiltersUsed_andNoSinglePerson() {
        MockHttpServletRequest request = requestWithVariables("/api/v1/admin/system/recent-imports", Map.of());
        request.addParameter("page", "0");

        interceptor.afterCompletion(request, responseWith(200), new Object(), null);

        verify(auditService).record(eq(adminId), eq(AdminUserDataReadAuditInterceptor.LISTED_ACTION), eq("User"),
                isNull(), argThat(metadata -> Map.of("page", "0").equals(metadata.get("query"))
                        && !metadata.containsKey("unclassified")));
    }

    @Test
    void countsAndHealthAreNotRecorded() {
        interceptor.afterCompletion(requestWithVariables("/api/v1/admin/stats/overview", Map.of()),
                responseWith(200), new Object(), null);
        interceptor.afterCompletion(requestWithVariables("/api/v1/admin/system/health", Map.of()),
                responseWith(200), new Object(), null);

        verifyNoInteractions(auditService);
    }

    /** A route missing from the table is over-recorded, not silently skipped. */
    @Test
    void aRouteTheTableDoesNotNameIsRecordedAsAListing_markedUnclassified() {
        interceptor.afterCompletion(requestWithVariables("/api/v1/admin/some-new-route", Map.of()),
                responseWith(200), new Object(), null);

        verify(auditService).record(eq(adminId), eq(AdminUserDataReadAuditInterceptor.LISTED_ACTION), eq("User"),
                isNull(), argThat(metadata -> Boolean.TRUE.equals(metadata.get("unclassified"))));
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
