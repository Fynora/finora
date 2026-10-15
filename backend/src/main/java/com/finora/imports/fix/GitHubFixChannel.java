package com.finora.imports.fix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The automatic Claude fix job's only connection to GitHub, and it only ever calls out: nothing on
 * GitHub calls production (Cloudflare's bot challenge would block it anyway). See
 * docs/superpowers/specs/2026-10-15-claude-fix-job-design.md, sections 2 and 4.
 *
 * <p>Calls to the private repository use {@code app.claude-fix.github-token}. Calls to the public
 * repository (its PRs and the compare that says whether a fix is deployed) use
 * {@code app.claude-fix.public-read-token} when it is set, and the private-repository token
 * otherwise: a fine-grained token limited to the private repository can still read a public
 * repository, but not a private one, so the read-only second token is what keeps the follow-up
 * working if Finora goes private.
 *
 * <p>A failed call throws {@link FixChannelException} naming the call and the status, never a
 * response body, and nothing here logs: a body could echo what was sent.
 */
@Component
public class GitHubFixChannel implements FixChannel {

    public enum RunState { IN_PROGRESS, SUCCEEDED, FAILED }

    public enum PrState { OPEN, MERGED, CLOSED }

    /** The two PRs one attempt opened. */
    public record PrPair(int publicPr, PrState publicState, int privatePr) {}

    static final String FIX_WORKFLOW_PATH = ".github/workflows/fix.yml";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    private final ClaudeFixProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public GitHubFixChannel(ClaudeFixProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** Puts {@code incoming/<job>-a<n>.trace}, then the {@code .json} whose push starts the workflow. */
    @Override
    public String send(UUID jobId, int attempt, String trace, String metaJson) {
        String name = "incoming/" + jobId + "-a" + attempt;
        String message = "held item " + jobId + " attempt " + attempt;
        put("put trace", name + ".trace", message, trace);
        JsonNode second = put("put meta", name + ".json", message, metaJson);
        String sha = second.path("commit").path("sha").asText("");
        if (sha.isEmpty()) throw new FixChannelException("put meta (no commit sha)", 200);
        return sha;
    }

    /** The fix workflow's run for the commit that sent an attempt, if GitHub has one yet. */
    public Optional<RunState> runFor(String commitSha) {
        JsonNode runs = get("read runs", privateRepo() + "/actions/runs?head_sha=" + encode(commitSha), false)
                .path("workflow_runs");
        for (JsonNode run : runs) {
            if (!FIX_WORKFLOW_PATH.equals(run.path("path").asText())) continue;
            if (!"completed".equals(run.path("status").asText())) return Optional.of(RunState.IN_PROGRESS);
            return Optional.of("success".equals(run.path("conclusion").asText())
                    ? RunState.SUCCEEDED : RunState.FAILED);
        }
        return Optional.empty();
    }

    /** The public and private PRs whose head branch is {@code fix/<job>-a<n>}, present only when both are. */
    public Optional<PrPair> prsFor(UUID jobId, int attempt) {
        String query = "/pulls?head=" + encode(owner() + ":fix/" + jobId + "-a" + attempt) + "&state=all&per_page=5";
        JsonNode publicPrs = get("read public PRs", publicRepo() + query, true);
        JsonNode privatePrs = get("read private PRs", privateRepo() + query, false);
        if (publicPrs.isEmpty() || privatePrs.isEmpty()) return Optional.empty();
        JsonNode pub = publicPrs.get(0);
        PrState state = !pub.path("merged_at").isNull() && !pub.path("merged_at").asText("").isEmpty()
                ? PrState.MERGED
                : "open".equals(pub.path("state").asText()) ? PrState.OPEN : PrState.CLOSED;
        return Optional.of(new PrPair(pub.path("number").asInt(), state, privatePrs.get(0).path("number").asInt()));
    }

    /** The public PR's merge commit, once it is merged. */
    public Optional<String> mergeCommit(int publicPr) {
        JsonNode pr = get("read public PR", publicRepo() + "/pulls/" + publicPr, true);
        if (!pr.path("merged").asBoolean(false)) return Optional.empty();
        String sha = pr.path("merge_commit_sha").asText("");
        return sha.isEmpty() ? Optional.empty() : Optional.of(sha);
    }

    /** Whether the deployed commit contains the merge commit. No deployed commit (local dev) is no. */
    public boolean deployedContains(String mergeSha, String deployedSha) {
        if (deployedSha == null || deployedSha.isBlank()) return false;
        String status = get("compare", publicRepo() + "/compare/" + encode(mergeSha) + "..." + encode(deployedSha), true)
                .path("status").asText();
        return "ahead".equals(status) || "identical".equals(status);
    }

    /**
     * Creates the file on the incoming branch. A file already there (a resend after a send that
     * failed half way) answers 422; it is then replaced, which GitHub allows only with its current
     * blob sha.
     */
    private JsonNode put(String what, String path, String message, String content) {
        try {
            return call(what, putRequest(path, message, content, null));
        } catch (FixChannelException e) {
            if (e.status() != 422) throw e;
        }
        String existing = get(what + " (existing file)", privateRepo() + "/contents/" + path + "?ref="
                + encode(properties.getIncomingBranch()), false).path("sha").asText("");
        if (existing.isEmpty()) throw new FixChannelException(what, 422);
        return call(what, putRequest(path, message, content, existing));
    }

    private HttpRequest putRequest(String path, String message, String content, String existingSha) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        body.put("branch", properties.getIncomingBranch());
        if (existingSha != null) body.put("sha", existingSha);
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new FixChannelException("put (request not written)", 0);
        }
        return request(privateRepo() + "/contents/" + path, false)
                .PUT(HttpRequest.BodyPublishers.ofString(json)).header("Content-Type", "application/json").build();
    }

    private JsonNode get(String what, String path, boolean publicRepository) {
        return call(what, request(path, publicRepository).GET().build());
    }

    private HttpRequest.Builder request(String path, boolean publicRepository) {
        String token = properties.getGithubToken();
        if (publicRepository && !properties.getPublicReadToken().isBlank()) token = properties.getPublicReadToken();
        return HttpRequest.newBuilder(URI.create(properties.getApiBase() + path))
                .timeout(READ_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "finora-claude-fix");
    }

    private JsonNode call(String what, HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new FixChannelException(what, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FixChannelException(what, 0);
        }
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new FixChannelException(what, response.statusCode());
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new FixChannelException(what + " (unreadable response)", response.statusCode());
        }
    }

    private String privateRepo() { return "/repos/" + properties.getPrivateRepo(); }
    private String publicRepo() { return "/repos/" + properties.getPublicRepo(); }
    private String owner() { return properties.getPublicRepo().substring(0, properties.getPublicRepo().indexOf('/')); }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
