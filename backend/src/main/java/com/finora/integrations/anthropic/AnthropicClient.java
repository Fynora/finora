package com.finora.integrations.anthropic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finora.config.FynProperties;
import com.finora.exception.ApiException;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The only {@link LlmClient} implementation -- see that interface's doc comment for why this is a
 * seam, not a provider hierarchy. Wraps Anthropic's Messages API
 * (https://docs.anthropic.com/en/api/messages), one HTTP call per {@link #complete}. Phase 4 adds
 * tool-use (plan §6): the multi-turn loop itself lives in the caller (see {@link LlmClient}'s own
 * doc for why), this class only translates one request/response pair to and from Anthropic's wire
 * format, tool blocks included.
 *
 * <p>Same retry posture as {@link com.finora.integrations.google.GmailApiClient}: automatic
 * retries disabled. A 429 here means Fyn's own rate limit is spent for this key; retrying
 * immediately makes an already-throttled situation worse, and every caller of {@link #complete}
 * already runs behind {@code ai_audit_log}-backed cost governance (plan §4.4) that should decide
 * whether to try again, not a transport-layer default.
 */
@Component
public class AnthropicClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicClient.class);

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    // Fyn's prompts are short by design (plan §3: aggregate-and-compose, never raw), so this is
    // generous headroom for Haiku 4.5, not a tuned production ceiling.
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final FynProperties properties;
    private final RestClient restClient;

    public AnthropicClient(FynProperties properties) {
        this.properties = properties;
        ClientHttpRequestFactorySettings timeouts = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(CONNECT_TIMEOUT)
                .withReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.httpComponents()
                        .withHttpClientCustomizer(HttpClientBuilder::disableAutomaticRetries)
                        .build(timeouts))
                .build();
    }

    /** Anthropic's request wire shape -- never exposed past this class, see {@link LlmClient}'s
     *  doc comment. {@code content} is a {@code List<Object>}, each element one of the three
     *  request-side block records below -- Jackson serializes each by its own runtime type, so a
     *  mixed list needs no common interface or custom serializer. {@code system} is a one-element
     *  list, not a bare string -- {@code cache_control} can only be attached to a system *block*,
     *  per Anthropic's prompt-caching wire format (verified against
     *  platform.claude.com/docs/en/docs/build-with-claude/prompt-caching, now GA, no
     *  {@code anthropic-beta} header needed). */
    private record AnthropicRequest(String model, int max_tokens, List<SystemBlock> system,
                                     List<AnthropicMessage> messages, double temperature,
                                     List<AnthropicTool> tools) {}

    private record AnthropicMessage(String role, List<Object> content) {}

    /** {@code cache_control} deliberately uses a per-field {@code @JsonInclude(NON_NULL)}, not a
     *  class- or module-wide setting -- this file's own convention (see the block-shapes comment
     *  below) is precise shapes with no stray {@code null} fields, and only this one field is ever
     *  legitimately absent (every tool but the last in a request). A blanket NON_NULL policy was
     *  rejected for that same reason; this is the narrow exception, not a reversal. */
    private record AnthropicTool(String name, String description, Map<String, Object> input_schema,
                                  @JsonInclude(JsonInclude.Include.NON_NULL)
                                  CacheControl cache_control) {}

    /** {@code ttl} omitted (not {@code null}-guarded) -- every cache breakpoint this client writes
     *  uses the default 5-minute ephemeral window, which is exactly Fyn's real reuse window: the
     *  within-one-message tool-call loop ({@code FynChatOrchestrationService}'s {@code
     *  MAX_TOOL_ROUNDS}) and a user's next message in the same sitting, not an hour-later
     *  follow-up. */
    private record CacheControl(String type) {
        static final CacheControl EPHEMERAL = new CacheControl("ephemeral");
    }

    private record SystemBlock(String type, String text, CacheControl cache_control) {
        SystemBlock(String text) { this("text", text, CacheControl.EPHEMERAL); }
    }

    // Three precise request-block shapes, not one flexible record with unused-null fields --
    // Jackson's default (this codebase sets no global default-property-inclusion) serializes null
    // fields as literal `null` in the JSON, and a text block carrying `"tool_use_id":null` is a
    // needless, easy-to-misread deviation from what Anthropic's own examples show. Same narrow
    // per-field NON_NULL exception as AnthropicTool.cache_control above: only the LAST block of
    // the LAST message in a request ever carries one (see toAnthropicMessage).
    private record RequestTextBlock(String type, String text,
                                     @JsonInclude(JsonInclude.Include.NON_NULL)
                                     CacheControl cache_control) {
        RequestTextBlock(String text, CacheControl cacheControl) { this("text", text, cacheControl); }
    }

    private record RequestToolUseBlock(String type, String id, String name, Map<String, Object> input,
                                        @JsonInclude(JsonInclude.Include.NON_NULL)
                                        CacheControl cache_control) {
        RequestToolUseBlock(String id, String name, Map<String, Object> input, CacheControl cacheControl) {
            this("tool_use", id, name, input, cacheControl);
        }
    }

    private record RequestToolResultBlock(String type, String tool_use_id, String content,
                                           @JsonInclude(JsonInclude.Include.NON_NULL)
                                           CacheControl cache_control) {
        RequestToolResultBlock(String toolUseId, String content, CacheControl cacheControl) {
            this("tool_result", toolUseId, content, cacheControl);
        }
    }

    private record AnthropicResponse(String model, List<ResponseContentBlock> content, String stop_reason,
                                      Usage usage) {}

    /** One flexible shape for parsing, unlike the three precise ones above for writing: reading
     *  never serializes this back out, so unused-per-type fields deserializing as null is harmless
     *  -- Jackson binds only the fields actually present in a given block's JSON. */
    private record ResponseContentBlock(String type, String text, String id, String name,
                                         Map<String, Object> input) {}

    // cache_creation_input_tokens/cache_read_input_tokens: absent entirely on a response with no
    // caching involved -- Jackson's record support defaults a missing int field to 0, same as
    // input_tokens/output_tokens already relied on implicitly before this, so no explicit
    // null-handling is needed here.
    private record Usage(int input_tokens, int output_tokens, int cache_creation_input_tokens,
                          int cache_read_input_tokens) {}

    @Override
    public LlmCompletion complete(LlmRequest request) {
        if (!properties.hasApiKey()) {
            // Guarded again at the orchestration layer (FynAvailabilityGuard) before this is ever
            // reached in a real call path -- this is a defensive last line, not the primary check,
            // since a unit test or a future caller could construct this client directly.
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY is not configured -- Fyn cannot make an LLM call.");
        }

        // Cache breakpoints (prompt caching, see AnthropicRequest's doc comment): the system
        // block (always -- SYSTEM_PROMPT never changes call to call), the last tool definition
        // (covers the whole static tools array -- Anthropic caches everything up to and including
        // a marked block), and the last content block of the last message (covers the whole
        // conversation-so-far prefix, which is exactly what repeats verbatim across
        // FynChatOrchestrationService's tool-call rounds and a user's next message). Below
        // Haiku's minimum cacheable length this silently costs nothing and caches nothing -- see
        // that class's own note on why this is a free option, not a guaranteed win, for Fyn's
        // deliberately short prompts.
        List<AnthropicMessage> messages = new ArrayList<>();
        for (int i = 0; i < request.messages().size(); i++) {
            messages.add(toAnthropicMessage(request.messages().get(i), i == request.messages().size() - 1));
        }
        List<AnthropicTool> tools = new ArrayList<>();
        for (int i = 0; i < request.tools().size(); i++) {
            LlmTool t = request.tools().get(i);
            CacheControl cc = i == request.tools().size() - 1 ? CacheControl.EPHEMERAL : null;
            tools.add(new AnthropicTool(t.name(), t.description(), t.inputSchema(), cc));
        }
        AnthropicRequest body = new AnthropicRequest(
                properties.getModel(), request.maxTokens(), List.of(new SystemBlock(request.systemPrompt())),
                messages, request.temperature(), tools);

        try {
            AnthropicResponse response = restClient.post()
                    .uri(URI.create(properties.getAnthropicBaseUrl() + "/v1/messages"))
                    .header("x-api-key", properties.getAnthropicApiKey())
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("content-type", "application/json")
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        int status = res.getStatusCode().value();
                        if (status == 401) {
                            // Not transient -- retrying with the same bad key never succeeds.
                            // Distinct from every other 4xx so a caller (and an alert) can tell
                            // "misconfigured" apart from "try again."
                            throw new ApiException(HttpStatus.BAD_GATEWAY,
                                    "Anthropic rejected the API key (401). Check ANTHROPIC_API_KEY.");
                        }
                        if (status == 429) {
                            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                    "Anthropic rate limit reached (429).");
                        }
                        log.warn("Anthropic refused a completion request with {}", status);
                        throw new ApiException(HttpStatus.BAD_GATEWAY,
                                "Anthropic refused the request.");
                    })
                    .body(AnthropicResponse.class);

            if (response == null || response.content() == null || response.content().isEmpty()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Anthropic returned an empty completion.");
            }

            String text = response.content().stream()
                    .filter(c -> "text".equals(c.type()))
                    .map(ResponseContentBlock::text)
                    .reduce("", String::concat);
            List<LlmClient.ToolUse> toolUses = response.content().stream()
                    .filter(c -> "tool_use".equals(c.type()))
                    .map(c -> new LlmClient.ToolUse(c.id(), c.name(), c.input()))
                    .toList();

            Usage usage = response.usage();
            return new LlmCompletion(
                    toolUses.isEmpty() ? text : null, toolUses, response.model(),
                    usage != null ? usage.input_tokens() : 0,
                    usage != null ? usage.cache_creation_input_tokens() : 0,
                    usage != null ? usage.cache_read_input_tokens() : 0,
                    usage != null ? usage.output_tokens() : 0,
                    response.stop_reason());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Anthropic completion call failed transiently: {}", e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not reach Anthropic. Try again shortly.");
        }
    }

    /** A message carries exactly one kind of content in this codebase's usage (plain text, a
     *  tool-use replay, or tool results) -- {@link LlmMessage}'s own factory methods only ever
     *  construct one at a time, never a mix, so branching on which is non-empty is unambiguous. */
    private AnthropicMessage toAnthropicMessage(LlmMessage message, boolean cacheLastBlock) {
        List<Object> content;
        if (!message.toolUses().isEmpty()) {
            List<ToolUse> uses = message.toolUses();
            content = new ArrayList<>();
            for (int i = 0; i < uses.size(); i++) {
                CacheControl cc = cacheLastBlock && i == uses.size() - 1 ? CacheControl.EPHEMERAL : null;
                content.add(new RequestToolUseBlock(uses.get(i).id(), uses.get(i).name(), uses.get(i).input(), cc));
            }
        } else if (!message.toolResults().isEmpty()) {
            List<ToolResult> results = message.toolResults();
            content = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                CacheControl cc = cacheLastBlock && i == results.size() - 1 ? CacheControl.EPHEMERAL : null;
                content.add(new RequestToolResultBlock(results.get(i).toolUseId(), results.get(i).content(), cc));
            }
        } else {
            content = List.of(new RequestTextBlock(message.content(), cacheLastBlock ? CacheControl.EPHEMERAL : null));
        }
        return new AnthropicMessage(message.role(), content);
    }
}
