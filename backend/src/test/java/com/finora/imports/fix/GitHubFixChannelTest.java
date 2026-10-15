package com.finora.imports.fix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The outbound GitHub client, against a local server standing in for api.github.com. */
class GitHubFixChannelTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID JOB = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** One request the stand-in received. */
    record Seen(String method, String pathAndQuery, Map<String, String> headers, String body) {}

    private HttpServer server;
    private final List<Seen> seen = new ArrayList<>();
    /** Path-and-query prefix to {status, body}; the longest matching prefix answers. */
    private final Map<String, Object[]> answers = new ConcurrentHashMap<>();
    private GitHubFixChannel channel;
    private ClaudeFixProperties properties;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        properties = new ClaudeFixProperties();
        properties.setApiBase("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setGithubToken("private-token");
        channel = new GitHubFixChannel(properties, JSON);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private synchronized void handle(HttpExchange exchange) throws IOException {
        String pathAndQuery = exchange.getRequestURI().getRawPath()
                + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
        Map<String, String> headers = new ConcurrentHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        seen.add(new Seen(exchange.getRequestMethod(), pathAndQuery, headers, body));
        Object[] answer = answers.entrySet().stream()
                .filter(e -> pathAndQuery.startsWith(e.getKey()))
                .max((a, b) -> Integer.compare(a.getKey().length(), b.getKey().length()))
                .map(Map.Entry::getValue)
                .orElse(new Object[] {404, "{\"message\":\"Not Found\"}"});
        byte[] bytes = ((String) answer[1]).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders((int) answer[0], bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void answer(String prefix, int status, String body) {
        answers.put(prefix, new Object[] {status, body});
    }

    private static final String PRIVATE = "/repos/Fynora/finora-statement-traces";
    private static final String PUBLIC = "/repos/Fynora/finora";

    @Test
    void sendPutsTheTraceThenTheMetaOnTheIncomingBranchAndReturnsTheSecondSha() {
        answer(PRIVATE + "/contents/incoming/" + JOB + "-a1.trace", 201, "{\"commit\":{\"sha\":\"first\"}}");
        answer(PRIVATE + "/contents/incoming/" + JOB + "-a1.json", 201, "{\"commit\":{\"sha\":\"second\"}}");

        String sha = channel.send(JOB, 1, "trace text", "{\"reference\":\"x\"}");

        assertThat(sha).isEqualTo("second");
        assertThat(seen).extracting(Seen::method).containsExactly("PUT", "PUT");
        assertThat(seen).extracting(Seen::pathAndQuery).containsExactly(
                PRIVATE + "/contents/incoming/" + JOB + "-a1.trace",
                PRIVATE + "/contents/incoming/" + JOB + "-a1.json");
        assertThat(seen.get(0).headers()).containsEntry("authorization", "Bearer private-token")
                .containsEntry("x-github-api-version", "2022-11-28");
    }

    @Test
    void sendBase64EncodesContentAndCarriesNoOtherFields() throws Exception {
        answer(PRIVATE + "/contents/incoming/", 201, "{\"commit\":{\"sha\":\"s\"}}");

        channel.send(JOB, 2, "trace text", "{\"m\":1}");

        JsonNode trace = JSON.readTree(seen.get(0).body());
        List<String> fields = new ArrayList<>();
        trace.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("message", "content", "branch");
        assertThat(trace.get("message").asText()).isEqualTo("held item " + JOB + " attempt 2");
        assertThat(trace.get("branch").asText()).isEqualTo("incoming");
        assertThat(new String(Base64.getDecoder().decode(trace.get("content").asText()), StandardCharsets.UTF_8))
                .isEqualTo("trace text");
        JsonNode meta = JSON.readTree(seen.get(1).body());
        assertThat(new String(Base64.getDecoder().decode(meta.get("content").asText()), StandardCharsets.UTF_8))
                .isEqualTo("{\"m\":1}");
    }

    @Test
    void aNon2xxThrowsWithTheCallAndStatusNotTheBody() {
        answer(PRIVATE + "/contents/incoming/", 500, "{\"message\":\"secret detail from github\"}");

        assertThatThrownBy(() -> channel.send(JOB, 1, "trace text", "{}"))
                .isInstanceOf(FixChannelException.class)
                .hasMessage("put trace failed with status 500")
                .hasMessageNotContaining("secret detail");
        assertThat(seen).hasSize(1);
    }

    @Test
    void anUnreachableServerThrowsWithStatusZero() {
        server.stop(0);

        assertThatThrownBy(() -> channel.send(JOB, 1, "trace text", "{}"))
                .isInstanceOf(FixChannelException.class)
                .hasMessage("put trace failed with status 0");
    }

    @Test
    void runForReadsTheRunByHeadSha() {
        answer(PRIVATE + "/actions/runs?head_sha=abc", 200, "{\"workflow_runs\":["
                + "{\"path\":\".github/workflows/traces.yml\",\"status\":\"completed\",\"conclusion\":\"failure\"},"
                + "{\"path\":\".github/workflows/fix.yml\",\"status\":\"completed\",\"conclusion\":\"success\"}]}");
        assertThat(channel.runFor("abc")).contains(GitHubFixChannel.RunState.SUCCEEDED);

        answer(PRIVATE + "/actions/runs?head_sha=def", 200,
                "{\"workflow_runs\":[{\"path\":\".github/workflows/fix.yml\",\"status\":\"in_progress\",\"conclusion\":null}]}");
        assertThat(channel.runFor("def")).contains(GitHubFixChannel.RunState.IN_PROGRESS);

        answer(PRIVATE + "/actions/runs?head_sha=ghi", 200,
                "{\"workflow_runs\":[{\"path\":\".github/workflows/fix.yml\",\"status\":\"completed\",\"conclusion\":\"cancelled\"}]}");
        assertThat(channel.runFor("ghi")).contains(GitHubFixChannel.RunState.FAILED);

        answer(PRIVATE + "/actions/runs?head_sha=jkl", 200, "{\"workflow_runs\":[]}");
        assertThat(channel.runFor("jkl")).isEmpty();
    }

    @Test
    void prsForFindsBothByBranchName() {
        String head = "head=Fynora%3Afix%2F" + JOB + "-a2";
        answer(PUBLIC + "/pulls?" + head, 200,
                "[{\"number\":41,\"state\":\"closed\",\"merged_at\":\"2026-10-15T10:00:00Z\",\"merge_commit_sha\":\"m1\"}]");
        answer(PRIVATE + "/pulls?" + head, 200, "[{\"number\":7,\"state\":\"open\",\"merged_at\":null}]");

        Optional<GitHubFixChannel.PrPair> pair = channel.prsFor(JOB, 2);

        assertThat(pair).isPresent();
        assertThat(pair.get().publicPr()).isEqualTo(41);
        assertThat(pair.get().publicState()).isEqualTo(GitHubFixChannel.PrState.MERGED);
        assertThat(pair.get().privatePr()).isEqualTo(7);
        assertThat(seen).extracting(Seen::pathAndQuery).allMatch(p -> p.contains("state=all"));
    }

    @Test
    void prsForIsEmptyUnlessBothExist() {
        String head = "head=Fynora%3Afix%2F" + JOB + "-a1";
        answer(PUBLIC + "/pulls?" + head, 200, "[]");
        answer(PRIVATE + "/pulls?" + head, 200, "[{\"number\":7,\"state\":\"open\",\"merged_at\":null}]");

        assertThat(channel.prsFor(JOB, 1)).isEmpty();
    }

    @Test
    void publicCallsUseThePublicReadTokenWhenOneIsSet() {
        properties.setPublicReadToken("public-read-token");
        String head = "head=Fynora%3Afix%2F" + JOB + "-a1";
        answer(PUBLIC + "/pulls?" + head, 200, "[{\"number\":41,\"state\":\"open\",\"merged_at\":null}]");
        answer(PRIVATE + "/pulls?" + head, 200, "[{\"number\":7,\"state\":\"open\",\"merged_at\":null}]");

        channel.prsFor(JOB, 1);

        assertThat(seen).filteredOn(s -> s.pathAndQuery().startsWith(PUBLIC + "/"))
                .extracting(s -> s.headers().get("authorization")).containsOnly("Bearer public-read-token");
        assertThat(seen).filteredOn(s -> s.pathAndQuery().startsWith(PRIVATE + "/"))
                .extracting(s -> s.headers().get("authorization")).containsOnly("Bearer private-token");
    }

    @Test
    void mergeCommitIsTheMergedPullsMergeSha() {
        answer(PUBLIC + "/pulls/41", 200, "{\"merged\":true,\"merge_commit_sha\":\"m1\"}");
        answer(PUBLIC + "/pulls/42", 200, "{\"merged\":false,\"merge_commit_sha\":\"test-merge\"}");

        assertThat(channel.mergeCommit(41)).contains("m1");
        assertThat(channel.mergeCommit(42)).isEmpty();
    }

    @Test
    void deployedContainsIsTrueForAheadAndIdenticalOnly() {
        answer(PUBLIC + "/compare/m1...d1", 200, "{\"status\":\"ahead\"}");
        answer(PUBLIC + "/compare/m1...d2", 200, "{\"status\":\"identical\"}");
        answer(PUBLIC + "/compare/m1...d3", 200, "{\"status\":\"behind\"}");
        answer(PUBLIC + "/compare/m1...d4", 200, "{\"status\":\"diverged\"}");

        assertThat(channel.deployedContains("m1", "d1")).isTrue();
        assertThat(channel.deployedContains("m1", "d2")).isTrue();
        assertThat(channel.deployedContains("m1", "d3")).isFalse();
        assertThat(channel.deployedContains("m1", "d4")).isFalse();
    }

    @Test
    void noDeployedShaMeansNotDeployed() {
        assertThat(channel.deployedContains("m1", null)).isFalse();
        assertThat(channel.deployedContains("m1", " ")).isFalse();
        assertThat(seen).isEmpty();
    }
}
