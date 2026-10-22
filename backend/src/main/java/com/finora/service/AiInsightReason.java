package com.finora.service;

/**
 * How an AI insight attempt ended (docs/superpowers/specs/2026-10-22-ai-insights-foundation-design.md
 * §5.5). Recorded in ai_audit_log.reason_code and ai_insight_texts.reason_code for operators; the app
 * only ever sees "AI text", "rules text" or "generating".
 */
public enum AiInsightReason {
    OK,
    PROVIDER_TIMEOUT,
    PROVIDER_ERROR,
    RATE_LIMITED,
    INVALID_JSON,
    SCHEMA_MISMATCH,
    UNKNOWN_FACT,
    LITERAL_NUMBER,
    FACT_BINDING,
    DIRECTION_MISMATCH,
    PERIOD_MISMATCH,
    ADVICE,
    PRODUCT_WORD,
    PREDICTION,
    BAD_ORDER,
    EMPTY,
    PERSIST_FAILED,
    FREE_ALLOWANCE_USED,
    FREE_BUDGET_SPENT,
    ORG_BUDGET_SPENT,
    USER_DAILY_CAP,
    CONCURRENCY_LIMIT,
    NOT_IN_AUDIENCE
}
