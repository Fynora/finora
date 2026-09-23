package com.finora.controller;

import com.finora.dto.NavEventRequest;
import com.finora.entity.ClientPlatform;
import com.finora.observability.NavDestination;
import com.finora.observability.NavEntryPoint;
import com.finora.observability.NavGroup;
import com.finora.observability.NavigationMetrics;
import com.finora.support.ClientIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NavEventControllerTest {

    private final NavigationMetrics metrics = mock(NavigationMetrics.class);
    private final ClientIdentity clientIdentity = mock(ClientIdentity.class);
    private final NavEventController controller = new NavEventController(metrics, clientIdentity);

    @BeforeEach
    void defaultPlatform() {
        when(clientIdentity.platform()).thenReturn(ClientPlatform.WEB);
    }

    private static NavEventRequest.Event event(String destination, String group, String entry) {
        return new NavEventRequest.Event(destination, group, entry);
    }

    @Test
    void record_incrementsBothCountersForAValidEvent() {
        controller.record(new NavEventRequest(List.of(event("budgets", "planning", "group")), 0));

        verify(metrics).destinationOpened(NavDestination.BUDGETS, NavGroup.PLANNING, ClientPlatform.WEB);
        verify(metrics).entryPointUsed(NavEntryPoint.GROUP, ClientPlatform.WEB);
    }

    @Test
    void record_dropsAnUnknownDestination_andDoesNotBucketItAsOther() {
        controller.record(new NavEventRequest(List.of(event("not-a-destination", "planning", "group")), 0));

        verifyNoInteractions(metrics);
    }

    @Test
    void record_dropsOneBadEventWithoutDiscardingTheGoodOnesBesideIt() {
        when(clientIdentity.platform()).thenReturn(ClientPlatform.MOBILE_ANDROID);

        controller.record(new NavEventRequest(List.of(
                event("budgets", "planning", "group"),
                event("nope", "planning", "group"),
                event("goals", "planning", "tab")), 0));

        verify(metrics).destinationOpened(
                NavDestination.BUDGETS, NavGroup.PLANNING, ClientPlatform.MOBILE_ANDROID);
        verify(metrics).destinationOpened(
                NavDestination.GOALS, NavGroup.PLANNING, ClientPlatform.MOBILE_ANDROID);
        verify(metrics, times(2)).entryPointUsed(any(), eq(ClientPlatform.MOBILE_ANDROID));
    }

    @Test
    void record_dropsAHalfValidEventRatherThanRecordingAPartialTagSet() {
        // Valid destination, unrecognised group. Recording this would attribute a real destination
        // to a wrong or missing group and quietly skew the distribution the counter feeds.
        controller.record(new NavEventRequest(List.of(event("budgets", "not-a-group", "group")), 0));

        verifyNoInteractions(metrics);
    }

    @Test
    void record_toleratesAnEntirelyAbsentBody() {
        controller.record(null);

        verifyNoInteractions(metrics);
    }

    @Test
    void record_capsAnOversizedBatch() {
        List<NavEventRequest.Event> tooMany =
                Collections.nCopies(500, event("budgets", "planning", "group"));

        controller.record(new NavEventRequest(tooMany, 0));

        verify(metrics, atMost(NavEventController.MAX_BATCH)).destinationOpened(any(), any(), any());
    }

    @Test
    void record_incrementsSearchUsedOncePerReportedSearch() {
        controller.record(new NavEventRequest(List.of(), 3));

        verify(metrics, times(3)).searchUsed(ClientPlatform.WEB);
    }

    @Test
    void record_capsSearchCountAndToleratesAnAbsentOne() {
        controller.record(new NavEventRequest(List.of(), 9_999));
        verify(metrics, atMost(NavEventController.MAX_BATCH)).searchUsed(ClientPlatform.WEB);

        clearInvocations(metrics);
        controller.record(new NavEventRequest(List.of(), null));
        verify(metrics, never()).searchUsed(any());
    }

    @Test
    void controller_hasNoRepositoryOrPersistenceDependency() {
        // "Nothing is stored" is a design constraint, not an omission -- with no events table there
        // is no dataset to later re-identify, leak, or be compelled to produce. Asserted
        // structurally because the natural future regression is someone adding a repository "just
        // to keep the raw events for debugging."
        assertThat(NavEventController.class.getDeclaredFields())
                .allSatisfy(field -> assertThat(field.getType().getSimpleName())
                        .doesNotContain("Repository")
                        .doesNotContain("EntityManager"));
    }

    @Test
    void requestShape_carriesNoUserSessionOrDeviceField() {
        // The absence of identity is the entire basis for collecting this without consent. If a
        // field like userId is ever added, the consent question and the privacy-policy wording both
        // reopen -- so this fails loudly rather than letting it erode quietly.
        assertThat(NavEventRequest.Event.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactlyInAnyOrder("destination", "group", "entry");

        assertThat(NavEventRequest.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactlyInAnyOrder("events", "searches");
    }
}
