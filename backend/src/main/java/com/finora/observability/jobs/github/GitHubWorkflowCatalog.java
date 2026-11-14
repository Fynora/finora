package com.finora.observability.jobs.github;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The GitHub workflows the Background jobs page watches (design:
 * docs/superpowers/specs/2026-11-14-background-jobs-github-design.md, Sid's option B): the private
 * trace repository's four, and the public repository's nightly checks. Not CI on pull requests or
 * merges: GitHub already shows those. Runs on main count, except the Claude fix job's, which run on
 * the incoming branch production pushes to.
 *
 * <p>Crons are copied from each workflow file, in UTC. The public files are checked to exist by
 * GitHubWorkflowCatalogTest; the private repository's (traces.yml, retention.yml, privacy-scan.yml,
 * fix.yml) were confirmed in Fynora/finora-statement-traces on 2026-11-14.
 */
public final class GitHubWorkflowCatalog {

    static final String PRIVATE = "Fynora/finora-statement-traces";
    static final String PUBLIC = "Fynora/finora";

    private static final List<GitHubWorkflow> ALL = List.of(
            entry(PRIVATE, "traces.yml", "30 4 * * *", "Statement trace tests",
                    "Runs the private statement traces against the public reader every night."),
            entry(PRIVATE, "retention.yml", "0 5 * * *", "Statement trace retention",
                    "Deletes traces of statements whose customers deleted them."),
            entry(PRIVATE, "privacy-scan.yml", null, "Trace privacy scan",
                    "Checks every saved trace holds no customer details, on each push to main."),
            new GitHubWorkflow(GitHubWorkflow.keyFor(PRIVATE, "fix.yml"), "Claude fix runs",
                    "Claude proposes a reader fix for a held statement.", PRIVATE, "fix.yml", "incoming", null, null,
                    Set.of("Read the copy again, and run every test", "The guard refused the public PR")),
            entry(PUBLIC, "e2e-nightly.yml", "0 3 * * *", "Web app tests (nightly)",
                    "Runs the full browser test suite against main every night."),
            entry(PUBLIC, "maestro-nightly.yml", "15 3 * * *", "Phone app tests (nightly)",
                    "Runs the phone app's Maestro tests every night."),
            entry(PUBLIC, "gitleaks-nightly.yml", "30 3 * * *", "Secret scan (nightly)",
                    "Scans the whole public history for leaked keys every night."),
            entry(PUBLIC, "image-scan-nightly.yml", "0 4 * * *", "Container image scan (nightly)",
                    "Scans the backend's container image for known vulnerabilities every night."),
            entry(PUBLIC, "codeql.yml", "0 4 * * 1", "CodeQL code scan (weekly)",
                    "GitHub's security scan of the code, every Monday."),
            new GitHubWorkflow(GitHubWorkflow.keyFor(PUBLIC, "github-traffic-metrics.yml"), "GitHub traffic metrics",
                    "Saves GitHub's visitor numbers every 6 hours.", PUBLIC, "github-traffic-metrics.yml", "main",
                    "0 */6 * * *", "Needs a GitHub token from Sid", Set.of())
    );

    private GitHubWorkflowCatalog() {}

    private static GitHubWorkflow entry(String repo, String file, String cron, String name, String purpose) {
        return new GitHubWorkflow(GitHubWorkflow.keyFor(repo, file), name, purpose, repo, file, "main", cron, null, Set.of());
    }

    public static List<GitHubWorkflow> all() {
        return ALL;
    }

    public static Optional<GitHubWorkflow> find(String key) {
        return ALL.stream().filter(w -> w.key().equals(key)).findFirst();
    }
}
