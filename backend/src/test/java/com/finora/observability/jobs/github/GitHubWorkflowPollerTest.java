package com.finora.observability.jobs.github;

import com.finora.observability.jobs.BackgroundJobStore;
import com.finora.observability.jobs.JobOutcome;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitHubWorkflowPollerTest {

    private static final Instant T0 = Instant.parse("2026-11-14T03:00:00Z");
    private final GitHubRunsClient client = mock(GitHubRunsClient.class);
    private final BackgroundJobStore store = mock(BackgroundJobStore.class);
    private final GitHubWorkflowPoller poller = new GitHubWorkflowPoller(client, store);

    private static GitHubRunsClient.Run run(long id, String status, String conclusion) {
        return new GitHubRunsClient.Run(id, status, conclusion, T0, T0.plusSeconds(600), "https://github.com/r/" + id);
    }

    private static final GitHubWorkflow FIX = GitHubWorkflowCatalog.find("github:Fynora/finora-statement-traces/fix.yml").orElseThrow();
    private static final GitHubWorkflow E2E = GitHubWorkflowCatalog.find("github:Fynora/finora/e2e-nightly.yml").orElseThrow();

    @Test
    void aNewFailedRunIsRecordedWithItsStepNames() {
        when(client.runs("Fynora/finora", "e2e-nightly.yml", "main")).thenReturn(List.of(run(9, "completed", "failure")));
        when(client.failedSteps("Fynora/finora", 9)).thenReturn(List.of("Run Playwright", "Upload report"));

        poller.read(E2E);

        verify(store).recordExternalRun(eq(E2E.key()), eq(9L), eq("https://github.com/r/9"), eq(T0),
                eq(T0.plusSeconds(600)), eq(JobOutcome.CRASHED), eq("Failed: Run Playwright, Upload report"));
    }

    @Test
    void skippedAndCancelledRunsAreIgnored_theLatestOtherRunCounts() {
        when(client.runs("Fynora/finora", "e2e-nightly.yml", "main")).thenReturn(List.of(
                run(12, "completed", "skipped"), run(11, "completed", "cancelled"), run(10, "completed", "success")));

        poller.read(E2E);

        verify(store).recordExternalRun(eq(E2E.key()), eq(10L), anyString(), any(), any(), eq(JobOutcome.OK), isNull());
    }

    @Test
    void aRunInProgressIsRecordedAsRunning() {
        when(client.runs("Fynora/finora", "e2e-nightly.yml", "main")).thenReturn(List.of(run(13, "in_progress", null)));

        poller.read(E2E);

        verify(store).recordExternalRun(eq(E2E.key()), eq(13L), anyString(), eq(T0), isNull(), isNull(), isNull());
        verify(client, never()).failedSteps(anyString(), anyLong());
    }

    @Test
    void fixRefusedOrTestsFailedIsOk_tokenStepIsFailed() {
        assertThat(GitHubWorkflowPoller.outcomeOf(FIX, run(1, "completed", "failure"),
                List.of("The guard refused the public PR"))).isEqualTo(JobOutcome.OK);
        assertThat(GitHubWorkflowPoller.outcomeOf(FIX, run(1, "completed", "failure"),
                List.of("Read the copy again, and run every test"))).isEqualTo(JobOutcome.OK);
        assertThat(GitHubWorkflowPoller.outcomeOf(FIX, run(1, "completed", "failure"),
                List.of("Token to open the PRs"))).isEqualTo(JobOutcome.CRASHED);
        assertThat(GitHubWorkflowPoller.outcomeOf(FIX, run(1, "completed", "failure"),
                List.of("The guard refused the public PR", "Open the private PR"))).isEqualTo(JobOutcome.CRASHED);
        assertThat(GitHubWorkflowPoller.outcomeOf(E2E, run(1, "completed", "timed_out"), List.of())).isEqualTo(JobOutcome.CRASHED);
        assertThat(GitHubWorkflowPoller.outcomeOf(E2E, run(1, "completed", "startup_failure"), List.of())).isEqualTo(JobOutcome.CRASHED);
        assertThat(GitHubWorkflowPoller.outcomeOf(E2E, run(1, "completed", "neutral"), List.of())).isEqualTo(JobOutcome.OK);
    }

    @Test
    void aReadFailureIsAFailedItem_andOtherWorkflowsStillRead() {
        when(client.runs(anyString(), anyString(), anyString())).thenThrow(new GitHubReadException("read runs", 401));
        org.mockito.Mockito.doReturn(List.of(run(20, "completed", "success")))
                .when(client).runs("Fynora/finora", "e2e-nightly.yml", "main");

        int failed = poller.readAll();

        assertThat(failed).isEqualTo(GitHubWorkflowCatalog.all().stream().filter(w -> w.offReason() == null).count() - 1);
        verify(store).recordExternalRun(eq(E2E.key()), eq(20L), anyString(), any(), any(), eq(JobOutcome.OK), isNull());
    }

    @Test
    void offWorkflowsAreNotRead() {
        poller.readAll();
        verify(client, never()).runs(eq("Fynora/finora"), eq("github-traffic-metrics.yml"), anyString());
    }

    @Test
    void noRunsYetRecordsNothing() {
        when(client.runs("Fynora/finora", "e2e-nightly.yml", "main")).thenReturn(List.of());
        poller.read(E2E);
        verify(store, never()).recordExternalRun(anyString(), anyLong(), anyString(), any(), any(), any(), any());
    }

    @Test
    void outcomeStepsAreOnlyEverTheFixJobs() {
        assertThat(GitHubWorkflowCatalog.all()).filteredOn(w -> !w.outcomeSteps().isEmpty())
                .extracting(GitHubWorkflow::file).containsExactly("fix.yml");
        assertThat(E2E.outcomeSteps()).isEqualTo(Set.of());
    }
}
