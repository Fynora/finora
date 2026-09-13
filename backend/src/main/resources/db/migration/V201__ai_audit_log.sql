-- Fyn (docs/superpowers/specs/2026-09-13-fino-ai-implementation-plan.md, §4.3) Phase 1 foundation.
-- One row per LLM call across every Fyn surface (Phase 2's import-diagnosis assist, Phase 3's
-- insights narration, Phase 4's chat) -- independent of whether the call belongs to a persisted
-- chat_conversation (Phase 2/3 calls don't). Deliberately not folded into the existing audit_logs
-- table (V1): that table's JSONB metadata column can't be efficiently SUM()'d for the daily/monthly
-- cost governance queries this table exists for -- tokens/cost/latency need real typed columns.
--
-- No redaction-sweep column (contrast audit_logs.redacted_at, BH-044): by the plan's §3/§4.2 design,
-- tool_inputs/tool_outputs here are constrained to Tier 0/1 facts (aggregates only) and never carry
-- the raw transaction/account data that made audit_logs' redaction sweep necessary. If that
-- assumption is ever violated, this table needs the same treatment audit_logs got -- not before.
CREATE TABLE ai_audit_log (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL,
    conversation_id  UUID,
    model            VARCHAR(64) NOT NULL,
    prompt_version   VARCHAR(32) NOT NULL,
    temperature      NUMERIC(3, 2),
    tool_name        VARCHAR(64),
    tool_inputs      JSONB,
    tool_outputs     JSONB,
    tokens_in        INTEGER NOT NULL,
    tokens_out       INTEGER NOT NULL,
    cost             NUMERIC(12, 8) NOT NULL,
    latency_ms       INTEGER NOT NULL,
    error            TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Cost governance (plan §4.4): per-user daily cap and the org-wide monthly budget both aggregate
-- SUM(cost) over a date range, so both need created_at first in the index -- the per-user variant
-- adds user_id to avoid a full scan when checking one user's daily spend.
CREATE INDEX idx_ai_audit_log_created_at ON ai_audit_log(created_at);
CREATE INDEX idx_ai_audit_log_user_created_at ON ai_audit_log(user_id, created_at);
