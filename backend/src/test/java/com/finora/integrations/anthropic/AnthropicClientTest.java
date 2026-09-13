package com.finora.integrations.anthropic;

import com.finora.config.FynProperties;
import com.finora.exception.ApiException;
import com.finora.integrations.anthropic.LlmClient.LlmCompletion;
import com.finora.integrations.anthropic.LlmClient.LlmMessage;
import com.finora.integrations.anthropic.LlmClient.LlmRequest;
import com.finora.integrations.anthropic.LlmClient.LlmTool;
import com.finora.integrations.anthropic.LlmClient.ToolResult;
import com.finora.integrations.anthropic.LlmClient.ToolUse;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Same standard as {@code GmailApiClientTest}: executed against a real local HTTP server, not a
 * mocked {@code RestClient}, so the actual request Anthropic would receive and the actual
 * response-parsing logic are both exercised, not just a stubbed return value.
 */
class AnthropicClientTest {

    private HttpServer server;
    private AnthropicClient client;
    private FynProperties properties;

    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> seenApiKeyHeader = new AtomicReference<>();
    private final AtomicReference<String> seenVersionHeader = new AtomicReference<>();
    private final AtomicReference<String> seenRequestBody = new AtomicReference<>();

    @BeforeEach
    void startStubAnthropic() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpHandler handler = exchange -> {
            seenApiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            seenVersionHeader.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            seenRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] payload = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        };
        server.createContext("/v1/messages", handler);
        server.start();

        properties = new FynProperties();
        properties.setAnthropicApiKey("test-key-123");
        properties.setAnthropicBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setModel("claude-haiku-4-5-20251001");
        client = new AnthropicClient(properties);
    }

    @AfterEach
    void stopStubAnthropic() {
        server.stop(0);
    }

    @Test
    @DisplayName("sends the API key and Anthropic version headers, and the system/user prompt in the body")
    void sendsExpectedRequest() {
        status.set(200);
        body.set("""
                {"model":"claude-haiku-4-5-20251001","content":[{"type":"text","text":"You spent \\u20b94,200 on dining."}],
                 "stop_reason":"end_turn","usage":{"input_tokens":42,"output_tokens":11}}""");

        client.complete(LlmRequest.singleTurn("Narrate this fact, never give advice.",
                "How much did I spend on dining?", 200));

        assertThat(seenApiKeyHeader.get()).isEqualTo("test-key-123");
        assertThat(seenVersionHeader.get()).isEqualTo("2023-06-01");
        assertThat(seenRequestBody.get()).contains("\"model\":\"claude-haiku-4-5-20251001\"")
                .contains("\"max_tokens\":200")
                .contains("Narrate this fact, never give advice.")
                .contains("How much did I spend on dining?");
    }

    @Test
    @DisplayName("parses content/model/usage/stop_reason into an LlmCompletion")
    void parsesCompletion() {
        status.set(200);
        body.set("""
                {"model":"claude-haiku-4-5-20251001","content":[{"type":"text","text":"You spent \\u20b94,200 on dining."}],
                 "stop_reason":"end_turn","usage":{"input_tokens":42,"output_tokens":11}}""");

        LlmCompletion completion = client.complete(
                LlmRequest.singleTurn("system", "How much did I spend on dining?", 200));

        assertThat(completion.content()).isEqualTo("You spent ₹4,200 on dining.");
        assertThat(completion.model()).isEqualTo("claude-haiku-4-5-20251001");
        assertThat(completion.tokensIn()).isEqualTo(42);
        assertThat(completion.tokensOut()).isEqualTo(11);
        assertThat(completion.stopReason()).isEqualTo("end_turn");
    }

    @Test
    @DisplayName("concatenates multiple text content blocks")
    void concatenatesMultipleTextBlocks() {
        status.set(200);
        body.set("""
                {"model":"claude-haiku-4-5-20251001",
                 "content":[{"type":"text","text":"Part one. "},{"type":"text","text":"Part two."}],
                 "stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":5}}""");

        LlmCompletion completion = client.complete(LlmRequest.singleTurn("s", "u", 50));

        assertThat(completion.content()).isEqualTo("Part one. Part two.");
    }

    @Test
    @DisplayName("401 is classified as a non-transient config error, not a generic failure")
    void classifies401() {
        status.set(401);
        body.set("""
                {"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""");

        assertThatThrownBy(() -> client.complete(LlmRequest.singleTurn("s", "u", 50)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @Test
    @DisplayName("429 is classified distinctly as a rate limit, not a generic bad gateway")
    void classifies429() {
        status.set(429);
        body.set("""
                {"type":"error","error":{"type":"rate_limit_error","message":"rate limited"}}""");

        assertThatThrownBy(() -> client.complete(LlmRequest.singleTurn("s", "u", 50)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("5xx is classified as transient")
    void classifies5xx() {
        status.set(503);
        body.set("""
                {"type":"error","error":{"type":"overloaded_error","message":"overloaded"}}""");

        assertThatThrownBy(() -> client.complete(LlmRequest.singleTurn("s", "u", 50)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("Phase 4: sends tool definitions in the request body")
    void sendsToolDefinitions() {
        status.set(200);
        body.set("""
                {"model":"claude-haiku-4-5-20251001","content":[{"type":"text","text":"ok"}],
                 "stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":5}}""");
        LlmTool tool = new LlmTool("GET_BALANCE", "Returns the user's current total balance.",
                java.util.Map.of("type", "object", "properties", java.util.Map.of()));

        client.complete(LlmRequest.withTools("system", List.of(LlmMessage.user("what's my balance?")), 100,
                List.of(tool)));

        assertThat(seenRequestBody.get())
                .contains("\"name\":\"GET_BALANCE\"")
                .contains("\"description\":\"Returns the user's current total balance.\"")
                .contains("\"input_schema\":");
    }

    @Test
    @DisplayName("Phase 4: parses a tool_use content block into an LlmCompletion with no text")
    void parsesToolUseBlock() {
        status.set(200);
        body.set("""
                {"model":"claude-haiku-4-5-20251001",
                 "content":[{"type":"tool_use","id":"toolu_01","name":"GET_BALANCE","input":{}}],
                 "stop_reason":"tool_use","usage":{"input_tokens":50,"output_tokens":20}}""");

        LlmCompletion completion = client.complete(LlmRequest.singleTurn("s", "u", 200));

        assertThat(completion.requestsToolUse()).isTrue();
        assertThat(completion.content()).isNull();
        assertThat(completion.toolUses()).hasSize(1);
        ToolUse toolUse = completion.toolUses().get(0);
        assertThat(toolUse.id()).isEqualTo("toolu_01");
        assertThat(toolUse.name()).isEqualTo("GET_BALANCE");
        assertThat(completion.stopReason()).isEqualTo("tool_use");
    }

    @Test
    @DisplayName("Phase 4: replays a tool_use message and sends a tool_result message correctly")
    void sendsToolUseReplayAndToolResult() {
        status.set(200);
        body.set("""
                {"model":"claude-haiku-4-5-20251001","content":[{"type":"text","text":"Your balance is \\u20b950,000."}],
                 "stop_reason":"end_turn","usage":{"input_tokens":80,"output_tokens":10}}""");

        List<LlmMessage> messages = List.of(
                LlmMessage.user("what's my balance?"),
                LlmMessage.assistantToolUse(List.of(new ToolUse("toolu_01", "GET_BALANCE", java.util.Map.of()))),
                LlmMessage.toolResults(List.of(new ToolResult("toolu_01", "50000.00"))));

        LlmCompletion completion = client.complete(LlmRequest.withTools("s", messages, 200, List.of()));

        assertThat(completion.content()).contains("50,000");
        assertThat(seenRequestBody.get())
                .contains("\"type\":\"tool_use\"", "\"id\":\"toolu_01\"", "\"name\":\"GET_BALANCE\"")
                .contains("\"type\":\"tool_result\"", "\"tool_use_id\":\"toolu_01\"", "\"content\":\"50000.00\"");
    }

    @Test
    @DisplayName("refuses to call Anthropic at all when no API key is configured")
    void refusesWithoutApiKey() {
        properties.setAnthropicApiKey("");

        assertThatThrownBy(() -> client.complete(LlmRequest.singleTurn("s", "u", 50)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }
}
