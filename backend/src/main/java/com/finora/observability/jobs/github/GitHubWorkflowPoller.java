package com.finora.observability.jobs.github;

import com.finora.observability.jobs.BackgroundJobReport;
import com.finora.observability.jobs.BackgroundJobStore;
import com.finora.observability.jobs.JobOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Every 15 minutes, reads the latest run of each GitHub workflow the Background jobs page watches and
 * records it (part 2). A run is written once by its id, so a failure makes one failure row and one
 * email. A workflow that cannot be read (no token, an expired one, GitHub down) is a failed item of
 * this job's own run, so the page and the emails say so, and the workflow's row keeps its last result.
 */
@Component
public class GitHubWorkflowPoller {

    private static final Logger log = LoggerFactory.getLogger(GitHubWorkflowPoller.class);
    private static final Set<String> IGNORED = Set.of("skipped", "cancelled");
    private static final Set<String> FAILED = Set.of("failure", "timed_out", "startup_failure");

    private final GitHubRunsClient client;
    private final BackgroundJobStore store;

    public GitHubWorkflowPoller(GitHubRunsClient client, BackgroundJobStore store) {
        this.client = client;
        this.store = store;
    }

    @Scheduled(fixedDelayString = "${app.background-jobs.github.poll-interval-ms:900000}",
               initialDelayString = "${app.background-jobs.github.initial-delay-ms:60000}")
    public void poll() {
        readAll();
    }

    /** Reads every workflow that is not Off; returns how many could not be read. */
    int readAll() {
        int failed = 0;
        for (GitHubWorkflow workflow : GitHubWorkflowCatalog.all()) {
            if (workflow.offReason() != null) continue;
            try {
                read(workflow);
            } catch (RuntimeException e) {
                failed++;
                log.warn("Could not read GitHub workflow {}: {}", workflow.key(), e.getMessage());
                BackgroundJobReport.itemFailed(e);
            }
        }
        return failed;
    }

    void read(GitHubWorkflow workflow) {
        List<GitHubRunsClient.Run> runs = client.runs(workflow.repo(), workflow.file(), workflow.branch());
        GitHubRunsClient.Run run = runs.stream()
                .filter(r -> r.conclusion() == null || !IGNORED.contains(r.conclusion()))
                .findFirst().orElse(null);
        if (run == null) return;
        if (!"completed".equals(run.status())) {
            store.recordExternalRun(workflow.key(), run.id(), run.htmlUrl(), run.runStartedAt(), null, null, null);
            noteLastSuccess(workflow, runs);
            return;
        }
        List<String> failedSteps = FAILED.contains(run.conclusion())
                ? client.failedSteps(workflow.repo(), run.id()) : List.of();
        JobOutcome outcome = outcomeOf(workflow, run, failedSteps);
        String summary = outcome == JobOutcome.CRASHED
                ? "Failed: " + (failedSteps.isEmpty() ? run.conclusion() : String.join(", ", failedSteps))
                : null;
        store.recordExternalRun(workflow.key(), run.id(), run.htmlUrl(), run.runStartedAt(), run.updatedAt(),
                outcome, summary);
        noteLastSuccess(workflow, runs);
    }

    /** The newest successful run in the list, so a failing workflow still shows when it last passed. */
    private void noteLastSuccess(GitHubWorkflow workflow, List<GitHubRunsClient.Run> runs) {
        runs.stream().filter(r -> "success".equals(r.conclusion())).findFirst()
                .ifPresent(r -> store.noteExternalSuccess(workflow.key(), r.updatedAt()));
    }

    /**
     * success and anything not a failure is OK; failure, timed_out and startup_failure are CRASHED,
     * unless every failed step is one of the workflow's designed outcomes (the Claude fix job's
     * "no fix" and "a person finishes it").
     */
    static JobOutcome outcomeOf(GitHubWorkflow workflow, GitHubRunsClient.Run run, List<String> failedSteps) {
        if (!FAILED.contains(run.conclusion())) return JobOutcome.OK;
        if (!failedSteps.isEmpty() && workflow.outcomeSteps().containsAll(failedSteps)) return JobOutcome.OK;
        return JobOutcome.CRASHED;
    }
}
