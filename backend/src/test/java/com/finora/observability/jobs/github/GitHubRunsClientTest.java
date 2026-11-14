package com.finora.observability.jobs.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.imports.fix.ClaudeFixProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubRunsClientTest {

    private HttpServer server;
    private final Map<String, String> authBy = new ConcurrentHashMap<>();
    private final Map<String, Object[]> answers = new ConcurrentHashMap<>();
    private GitHubRunsClient client;
    private ClaudeFixProperties properties;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        properties = new ClaudeFixProperties();
        properties.setApiBase("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setGithubToken("private-token");
        properties.setPublicReadToken("public-token");
        client = new GitHubRunsClient(properties, new ObjectMapper());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
        authBy.put(path, String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
        Object[] answer = answers.getOrDefault(path, new Object[] {404, "{}"});
        byte[] body = ((String) answer[1]).getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders((Integer) answer[0], body.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(body); }
    }

    private static final String RUNS = """
            {"workflow_runs":[
              {"id":9,"status":"completed","conclusion":"failure","run_started_at":"2026-11-14T03:00:05Z",
               "updated_at":"2026-11-14T03:20:00Z","html_url":"https://github.com/x/runs/9"},
              {"id":8,"status":"completed","conclusion":"success","run_started_at":"2026-11-13T03:00:05Z",
               "updated_at":"2026-11-13T03:20:00Z","html_url":"https://github.com/x/runs/8"}]}""";

    @Test
    void readsMainRunsWithTheRightTokenPerRepo() {
        answers.put("/repos/Fynora/finora/actions/workflows/e2e-nightly.yml/runs?branch=main&per_page=10", new Object[] {200, RUNS});
        answers.put("/repos/Fynora/finora-statement-traces/actions/workflows/fix.yml/runs?branch=incoming&per_page=10", new Object[] {200, RUNS});

        List<GitHubRunsClient.Run> runs = client.runs("Fynora/finora", "e2e-nightly.yml", "main");
        client.runs("Fynora/finora-statement-traces", "fix.yml", "incoming");

        assertThat(runs).hasSize(2);
        assertThat(runs.get(0)).isEqualTo(new GitHubRunsClient.Run(9, "completed", "failure",
                Instant.parse("2026-11-14T03:00:05Z"), Instant.parse("2026-11-14T03:20:00Z"), "https://github.com/x/runs/9"));
        assertThat(authBy.get("/repos/Fynora/finora/actions/workflows/e2e-nightly.yml/runs?branch=main&per_page=10"))
                .isEqualTo("Bearer public-token");
        assertThat(authBy.get("/repos/Fynora/finora-statement-traces/actions/workflows/fix.yml/runs?branch=incoming&per_page=10"))
                .isEqualTo("Bearer private-token");
    }

    @Test
    void withNoPublicReadToken_thePublicRepoUsesTheMainToken() {
        properties.setPublicReadToken("");
        answers.put("/repos/Fynora/finora/actions/workflows/e2e-nightly.yml/runs?branch=main&per_page=10", new Object[] {200, RUNS});
        client.runs("Fynora/finora", "e2e-nightly.yml", "main");
        assertThat(authBy.get("/repos/Fynora/finora/actions/workflows/e2e-nightly.yml/runs?branch=main&per_page=10"))
                .isEqualTo("Bearer private-token");
    }

    @Test
    void failedStepsAreTheStepsThatFailed() {
        answers.put("/repos/Fynora/finora-statement-traces/actions/runs/9/jobs", new Object[] {200, """
                {"jobs":[{"steps":[{"name":"Set up job","conclusion":"success"},
                  {"name":"Token to open the PRs","conclusion":"failure"},
                  {"name":"Open the private PR","conclusion":"skipped"}]}]}"""});
        assertThat(client.failedSteps("Fynora/finora-statement-traces", 9)).containsExactly("Token to open the PRs");
    }

    @Test
    void a401IsAGitHubReadException_withoutTheToken() {
        answers.put("/repos/Fynora/finora/actions/workflows/e2e-nightly.yml/runs?branch=main&per_page=10",
                new Object[] {401, "{\"message\":\"Bad credentials\"}"});
        assertThatThrownBy(() -> client.runs("Fynora/finora", "e2e-nightly.yml", "main"))
                .isInstanceOf(GitHubReadException.class)
                .hasMessageContaining("401")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("public-token").doesNotContain("private-token"));
    }

    @Test
    void aBlankTokenIsAReadExceptionWithoutCallingGitHub() {
        properties.setGithubToken("");
        properties.setPublicReadToken("");
        assertThatThrownBy(() -> client.runs("Fynora/finora", "e2e-nightly.yml", "main"))
                .isInstanceOf(GitHubReadException.class).hasMessageContaining("no GitHub token");
        assertThat(authBy).isEmpty();
    }
}
