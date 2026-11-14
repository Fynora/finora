package com.finora.observability.jobs.github;

import java.util.Set;

/**
 * One GitHub workflow the Background jobs page watches.
 *
 * @param cronUtc      the workflow's own 5-field schedule, in UTC; null for one started by a push
 * @param offReason    set when the workflow is shown as Off on purpose (never read, never emails)
 * @param outcomeSteps steps whose failure is a designed outcome, not something broken
 */
public record GitHubWorkflow(String key, String name, String purpose, String repo, String file,
                             String cronUtc, String offReason, Set<String> outcomeSteps) {

    public static String keyFor(String repo, String file) {
        return "github:" + repo + "/" + file;
    }
}
