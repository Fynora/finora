package com.finora.observability.jobs.github;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubWorkflowCatalogTest {

    @Test
    void tenWorkflows_uniqueKeys_keysMatchRepoAndFile() {
        List<GitHubWorkflow> all = GitHubWorkflowCatalog.all();
        assertThat(all).hasSize(10);
        assertThat(all).extracting(GitHubWorkflow::key).doesNotHaveDuplicates();
        assertThat(all).allSatisfy(w -> {
            assertThat(w.key()).isEqualTo("github:" + w.repo() + "/" + w.file());
            assertThat(w.key()).isEqualTo(GitHubWorkflow.keyFor(w.repo(), w.file()));
            assertThat(w.name()).isNotBlank();
            assertThat(w.purpose()).isNotBlank().hasSizeLessThanOrEqualTo(120);
        });
        assertThat(GitHubWorkflowCatalog.find("github:Fynora/finora/e2e-nightly.yml")).isPresent();
        assertThat(GitHubWorkflowCatalog.find("github:Fynora/finora/nope.yml")).isEmpty();
    }

    /** A renamed public workflow would otherwise go silently unread. The private repo's files are
     *  not in this checkout; see the catalogue's comment. */
    @Test
    void everyPublicRepoFileExists() {
        for (GitHubWorkflow w : GitHubWorkflowCatalog.all()) {
            if (!w.repo().equals("Fynora/finora")) continue;
            assertThat(Files.exists(Path.of("..", ".github", "workflows", w.file())))
                    .as(w.file() + " exists under .github/workflows").isTrue();
        }
    }

    @Test
    void onlyTrafficMetricsIsOff() {
        assertThat(GitHubWorkflowCatalog.all()).filteredOn(w -> w.offReason() != null)
                .extracting(GitHubWorkflow::file).containsExactly("github-traffic-metrics.yml");
    }

    @Test
    void fixYmlListsItsTwoOutcomeSteps() {
        GitHubWorkflow fix = GitHubWorkflowCatalog.find("github:Fynora/finora-statement-traces/fix.yml").orElseThrow();
        assertThat(fix.outcomeSteps()).isEqualTo(Set.of(
                "Read the copy again, and run every test", "The guard refused the public PR"));
        assertThat(fix.cronUtc()).isNull();
    }

    @Test
    void cronsAreTheWorkflowFilesOwn() {
        assertThat(GitHubWorkflowCatalog.find("github:Fynora/finora-statement-traces/traces.yml").orElseThrow().cronUtc())
                .isEqualTo("30 4 * * *");
        assertThat(GitHubWorkflowCatalog.find("github:Fynora/finora/codeql.yml").orElseThrow().cronUtc())
                .isEqualTo("0 4 * * 1");
    }
}
