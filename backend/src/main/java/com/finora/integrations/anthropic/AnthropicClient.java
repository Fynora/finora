package com.finora.integrations.anthropic;

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
import java.util.List;

/**
 * The only {@link LlmClient} implementation -- see that interface's doc comment for why this is a
 * seam, not a provider hierarchy. Wraps Anthropic's Messages API
 * (https://docs.anthropic.com/en/api/messages), one call per {@link #complete}, no tool-use yet
 * (Phase 1 has no tools registered -- see {@code ToolRegistry}; Phases 2-4 add real tool-calling
 * on top of this same client without changing this class's shape, since tool definitions travel
 * in the request body Anthropic already accepts).
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
     *  doc comment. */
    private record AnthropicRequest(String model, int max_tokens, String system,
                                     List<AnthropicMessage> messages, double temperature) {}

    private record AnthropicMessage(String role, String content) {}

    private record AnthropicResponse(String model, List<ContentBlock> content, String stop_reason,
                                      Usage usage) {}

    private record ContentBlock(String type, String text) {}

    private record Usage(int input_tokens, int output_tokens) {}

    @Override
    public LlmCompletion complete(LlmRequest request) {
        if (!properties.hasApiKey()) {
            // Guarded again at the orchestration layer (FynAvailabilityGuard) before this is ever
            // reached in a real call path -- this is a defensive last line, not the primary check,
            // since a unit test or a future caller could construct this client directly.
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY is not configured -- Fyn cannot make an LLM call.");
        }

        List<AnthropicMessage> messages = request.messages().stream()
                .map(m -> new AnthropicMessage(m.role(), m.content()))
                .toList();
        AnthropicRequest body = new AnthropicRequest(
                properties.getModel(), request.maxTokens(), request.systemPrompt(),
                messages, request.temperature());

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
                    .map(ContentBlock::text)
                    .reduce("", String::concat);

            Usage usage = response.usage();
            return new LlmCompletion(
                    text, response.model(),
                    usage != null ? usage.input_tokens() : 0,
                    usage != null ? usage.output_tokens() : 0,
                    response.stop_reason());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Anthropic completion call failed transiently: {}", e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not reach Anthropic. Try again shortly.");
        }
    }
}
