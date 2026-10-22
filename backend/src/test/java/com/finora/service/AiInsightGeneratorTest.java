package com.finora.service;

import com.finora.config.FynProperties;
import com.finora.entity.AiAuditLog;
import com.finora.exception.ApiException;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.LlmCompletion;
import com.finora.integrations.anthropic.LlmClient.LlmRequest;
import com.finora.repository.AiAuditLogRepository;
import com.finora.service.AiInsightFacts.FactSheet;
import com.finora.service.AiInsightFacts.Period;
import com.finora.service.AiInsightFacts.Row;
import com.finora.service.AiInsightFacts.Subject;
import com.finora.service.AiInsightFacts.Unit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AI insights foundation §4.1, §5.4, §5.5: what is sent, what is recorded, how failures are named,
 *  and the bound on calls at once. Every name and amount is invented. */
class AiInsightGeneratorTest {

    private final UUID userId = UUID.randomUUID();
    private LlmClient llmClient;
    private AiAuditLogRepository auditLog;
    private FynProperties properties;
    private AiInsightGenerator generator;

    private static final FactSheet SHEET = new FactSheet("2026-09",
            List.of(AiInsightFacts.MONTH, new Subject("category_1", "Friend K rent", "[category-1]"),
                    new Subject("food", "Food", "Food")),
            List.of(AiInsightFacts.fact("month", "total", Period.THIS_MONTH, Unit.RUPEES, new BigDecimal("40000"), null),
                    AiInsightFacts.fact("category_1", "this_month", Period.THIS_MONTH, Unit.RUPEES, new BigDecimal("12000"), null),
                    AiInsightFacts.fact("food", "this_month", Period.THIS_MONTH, Unit.RUPEES, new BigDecimal("6200"), null)),
            List.of(new Row("row_1", "AMOUNT_CHANGED|vpa:flatmate.a|2026-09-12", "amount-changed", "month"),
                    new Row("row_2", "CATEGORY_HIGH|Friend K rent|2026-09", "running-high", "category_1")),
            List.of("2026-06", "2026-07", "2026-08"));

    private static final String PASSING_REPLY = """
            {"sentences":[{"type":"OBSERVATION","template":"Spending came to {month.total}."}],"order":["row_2","row_1"]}""";

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmClient.class);
        auditLog = mock(AiAuditLogRepository.class);
        properties = new FynProperties();
        properties.setInsightsMaxConcurrent(4);
        FynNameShields shields = mock(FynNameShields.class);
        when(shields.forUser(userId)).thenReturn(new FynNameShield(List.of("Sample User")));
        generator = new AiInsightGenerator(llmClient, shields, auditLog, properties);
    }

    private static LlmCompletion reply(String text) {
        return new LlmCompletion(text, List.of(), "claude-haiku-4-5-20251001", 1200, 90, "end_turn");
    }

    @Test
    void outboundRequestCarriesNoPrivateData() {
        when(llmClient.complete(any())).thenReturn(reply(PASSING_REPLY));

        generator.generate(userId, "STORY", SHEET, true, "FREE");

        ArgumentCaptor<LlmRequest> sent = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmClient).complete(sent.capture());
        String everything = sent.getValue().systemPrompt() + "\n"
                + sent.getValue().messages().stream().map(LlmClient.LlmMessage::content).reduce("", String::concat);
        assertThat(everything).doesNotContain("vpa:flatmate.a").doesNotContain("flatmate")
                .doesNotContain("Friend K").doesNotContain("100000000001").doesNotContain("user.sample@okbank")
                .doesNotContain("Sample User");
        assertThat(everything).contains("[category-1]").contains("row_2").contains("\"display\"");
        assertThat(sent.getValue().maxTokens()).isEqualTo(400);
    }

    @Test
    void storyAndWhyUseTheirOwnPromptsAndLimits() {
        when(llmClient.complete(any())).thenReturn(reply(PASSING_REPLY));
        generator.generate(userId, "STORY", SHEET, true, "PLUS");
        generator.generate(userId, "WHY", SHEET, false, "PLUS");

        ArgumentCaptor<LlmRequest> sent = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmClient, times(2)).complete(sent.capture());
        assertThat(sent.getAllValues().get(0).systemPrompt()).contains("2 to 4 sentences").contains("rank");
        assertThat(sent.getAllValues().get(1).systemPrompt()).contains("1 or 2 sentences").doesNotContain("rank");
        assertThat(sent.getAllValues().get(1).maxTokens()).isEqualTo(200);
    }

    @Test
    void auditRowHasPlanAndReasonButNoText() {
        when(llmClient.complete(any())).thenReturn(reply(PASSING_REPLY));

        AiInsightGenerator.Result result = generator.generate(userId, "STORY", SHEET, true, "FREE");

        assertThat(result.reason()).isEqualTo(AiInsightReason.OK);
        assertThat(result.template().order()).containsExactly("row_2", "row_1");
        assertThat(result.model()).isEqualTo("claude-haiku-4-5-20251001");
        ArgumentCaptor<AiAuditLog> row = ArgumentCaptor.forClass(AiAuditLog.class);
        verify(auditLog).save(row.capture());
        assertThat(row.getValue().getPlanCode()).isEqualTo("FREE");
        assertThat(row.getValue().getReasonCode()).isEqualTo("OK");
        assertThat(row.getValue().getToolName()).isEqualTo("AI_INSIGHT_STORY");
        assertThat(row.getValue().getPromptVersion()).isEqualTo(AiInsightGenerator.STORY_PROMPT_VERSION);
        assertThat(row.getValue().getToolInputs()).isNull();
        assertThat(row.getValue().getToolOutputs()).isNull();
        assertThat(row.getValue().getTokensIn()).isEqualTo(1200);
        assertThat(row.getValue().getCost()).isPositive();
        assertThat(row.getValue().getError()).isNull();
    }

    @Test
    void timeoutMapsToProviderTimeout() {
        when(llmClient.complete(any())).thenThrow(new ApiException(HttpStatus.GATEWAY_TIMEOUT, "timed out"));
        assertThat(generator.generate(userId, "WHY", SHEET, false, "PLUS").reason()).isEqualTo(AiInsightReason.PROVIDER_TIMEOUT);
        ArgumentCaptor<AiAuditLog> row = ArgumentCaptor.forClass(AiAuditLog.class);
        verify(auditLog).save(row.capture());
        assertThat(row.getValue().getReasonCode()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(row.getValue().getToolName()).isEqualTo("AI_INSIGHT_WHY");
    }

    @Test
    void rateLimitMapsToRateLimited() {
        when(llmClient.complete(any())).thenThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate limited"));
        assertThat(generator.generate(userId, "WHY", SHEET, false, "PLUS").reason()).isEqualTo(AiInsightReason.RATE_LIMITED);
    }

    @Test
    void anyOtherFailureIsAProviderError() {
        when(llmClient.complete(any())).thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "refused"));
        assertThat(generator.generate(userId, "WHY", SHEET, false, "PLUS").reason()).isEqualTo(AiInsightReason.PROVIDER_ERROR);
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(llmClient).complete(any());
        assertThat(generator.generate(userId, "WHY", SHEET, false, "PLUS").reason()).isEqualTo(AiInsightReason.PROVIDER_ERROR);
    }

    @Test
    void aFailingCheckIsReportedNotThrown() {
        when(llmClient.complete(any())).thenReturn(reply("""
                {"sentences":[{"type":"OBSERVATION","template":"Spending came to 40000."}]}"""));

        AiInsightGenerator.Result result = generator.generate(userId, "STORY", SHEET, false, "FREE");

        assertThat(result.reason()).isEqualTo(AiInsightReason.LITERAL_NUMBER);
        assertThat(result.template()).isNull();
        ArgumentCaptor<AiAuditLog> row = ArgumentCaptor.forClass(AiAuditLog.class);
        verify(auditLog).save(row.capture());
        assertThat(row.getValue().getReasonCode()).isEqualTo("LITERAL_NUMBER");
        // Spent tokens still cost money and count toward the budgets.
        assertThat(row.getValue().getCost()).isPositive();
    }

    @Test
    void anUnreadableReplyIsReported() {
        when(llmClient.complete(any())).thenReturn(reply("Food rose a lot."));
        assertThat(generator.generate(userId, "STORY", SHEET, false, "FREE").reason()).isEqualTo(AiInsightReason.INVALID_JSON);
    }

    @Test
    void fifthConcurrentCallIsRefusedWithoutCallingTheModel() throws Exception {
        CountDownLatch inside = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        when(llmClient.complete(any())).thenAnswer(inv -> {
            inside.countDown();
            release.await(10, TimeUnit.SECONDS);
            return reply(PASSING_REPLY);
        });
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<AiInsightGenerator.Result>> held = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                held.add(pool.submit(() -> generator.generate(userId, "WHY", SHEET, false, "PLUS")));
            }
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

            AiInsightGenerator.Result fifth = generator.generate(userId, "WHY", SHEET, false, "PLUS");

            assertThat(fifth.reason()).isEqualTo(AiInsightReason.CONCURRENCY_LIMIT);
            verify(llmClient, times(4)).complete(any());
            release.countDown();
            for (Future<AiInsightGenerator.Result> f : held) assertThat(f.get().reason()).isEqualTo(AiInsightReason.OK);
            // The permits come back: a call after the four finish goes through.
            assertThat(generator.generate(userId, "WHY", SHEET, false, "PLUS").reason()).isEqualTo(AiInsightReason.OK);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
