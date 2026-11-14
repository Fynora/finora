package com.finora.observability.jobs.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.imports.fix.ClaudeFixProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads GitHub workflow runs for the Background jobs page, with the Claude fix job's GitHub settings
 * (its token has Actions read on the private repository; the public-read token, when set, is used
 * for every other repository, as GitHubFixChannel does). Read-only.
 */
@Component
public class GitHubRunsClient {

    /** One workflow run, as much of it as the page needs. */
    public record Run(long id, String status, String conclusion, Instant runStartedAt, Instant updatedAt, String htmlUrl) {}

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    private final ClaudeFixProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public GitHubRunsClient(ClaudeFixProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** The workflow's latest runs on {@code branch}, newest first (up to 10). */
    public List<Run> runs(String repo, String file, String branch) {
        JsonNode body = get("read runs of " + file, repo, "/repos/" + repo + "/actions/workflows/"
                + encode(file) + "/runs?branch=" + encode(branch) + "&per_page=10");
        List<Run> runs = new ArrayList<>();
        for (JsonNode r : body.path("workflow_runs")) {
            Instant updated = instant(r.path("updated_at"));
            Instant started = instant(r.path("run_started_at"));
            runs.add(new Run(r.path("id").asLong(), text(r.path("status")), text(r.path("conclusion")),
                    started != null ? started : updated, updated, text(r.path("html_url"))));
        }
        return runs;
    }

    /** The names of the steps that failed in a run, across its jobs. */
    public List<String> failedSteps(String repo, long runId) {
        JsonNode body = get("read jobs of run " + runId, repo, "/repos/" + repo + "/actions/runs/" + runId + "/jobs");
        List<String> names = new ArrayList<>();
        for (JsonNode job : body.path("jobs")) {
            for (JsonNode step : job.path("steps")) {
                if ("failure".equals(text(step.path("conclusion")))) names.add(text(step.path("name")));
            }
        }
        return names;
    }

    private JsonNode get(String what, String repo, String path) {
        String token = tokenFor(repo);
        if (token.isBlank()) throw new GitHubReadException(what + " (no GitHub token set)", 0);
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getApiBase() + path))
                .timeout(READ_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "finora-background-jobs")
                .GET().build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new GitHubReadException(what, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubReadException(what, 0);
        }
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new GitHubReadException(what, response.statusCode());
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new GitHubReadException(what + " (unreadable response)", response.statusCode());
        }
    }

    private String tokenFor(String repo) {
        String main = properties.getGithubToken() == null ? "" : properties.getGithubToken();
        if (repo.equals(properties.getPrivateRepo())) return main;
        String publicToken = properties.getPublicReadToken();
        return publicToken != null && !publicToken.isBlank() ? publicToken : main;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    private static Instant instant(JsonNode node) {
        String s = text(node);
        return s == null || s.isBlank() ? null : Instant.parse(s);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
