-- AI insights foundation (docs/superpowers/specs/2026-10-22-ai-insights-foundation-design.md).
--
-- ai_insight_texts: one row per AI text attempt -- the month story (kind STORY, subject 'MONTH') or a
-- "Why?" answer (kind WHY, subject '<row kind>|<item key>'). The unique key covers every input that
-- changes the text: the fact sheet's hash, the prompt, the model and the checker policy (§5.4). A
-- request inserts a PENDING row first; only the request whose insert wins calls the model, so double
-- taps, retries and parallel requests make one call. FAILED rows are never served. The sheet the text
-- was written from is kept beside it, so any text can be traced to its facts (§4.3).
--
-- ai_insight_usage: the Free plan's monthly allowance (§6) -- "Why?" answers used and stories
-- generated, per user per calendar month in the user's own timezone. Updated with a single
-- conditional UPDATE so parallel taps can never pass the limit.
--
-- ON DELETE CASCADE to users, and AccountPurgeSweepService deletes both explicitly too: the purge
-- anonymizes the users row rather than deleting it, so the cascade never fires there (same trap as
-- insight_acknowledgements, V277).
CREATE TABLE ai_insight_texts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('STORY', 'WHY')),
    subject VARCHAR(200) NOT NULL,
    month VARCHAR(7) NOT NULL,
    facts_hash VARCHAR(64) NOT NULL,
    prompt_version VARCHAR(32) NOT NULL,
    model VARCHAR(64) NOT NULL,
    policy_version VARCHAR(32) NOT NULL,
    status VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'READY', 'FAILED')),
    sheet JSONB,
    text TEXT,
    row_order JSONB,
    reason_code VARCHAR(32),
    plan_code VARCHAR(20),
    regenerated_for_version BOOLEAN NOT NULL DEFAULT FALSE,
    feedback VARCHAR(16) CHECK (feedback IN ('HELPFUL', 'NOT_HELPFUL')),
    feedback_reason VARCHAR(16) CHECK (feedback_reason IN ('WRONG_NUMBER', 'NOT_USEFUL', 'CONFUSING')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, kind, subject, month, facts_hash, prompt_version, model, policy_version)
);
CREATE INDEX idx_ai_insight_texts_user_kind_month ON ai_insight_texts(user_id, kind, month, created_at);

CREATE TABLE ai_insight_usage (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    month VARCHAR(7) NOT NULL,
    why_used INT NOT NULL DEFAULT 0,
    story_generations INT NOT NULL DEFAULT 0,
    UNIQUE (user_id, month)
);

-- Which plan a call was made under (the Free budget slice, §6) and why it ended as it did (§5.5).
-- Nullable: Fyn chat and the other surfaces do not fill them yet.
ALTER TABLE ai_audit_log ADD COLUMN plan_code VARCHAR(20);
ALTER TABLE ai_audit_log ADD COLUMN reason_code VARCHAR(32);

-- FYN_INSIGHTS, unseeded since V205, so the narration endpoint refused every plan. Granted to all
-- three plans like FYN_CHAT: Free's taster (the newest month's story, 5 "Why?" a month) is enforced
-- in AiInsightService by plan code, not by leaving the key out.
INSERT INTO feature_entitlements (plan_id, feature_key, enabled)
    SELECT p.id, 'FYN_INSIGHTS', true FROM plans p
    WHERE p.code IN ('FREE', 'PLUS', 'PREMIUM')
      AND NOT EXISTS (SELECT 1 FROM feature_entitlements fe
                      WHERE fe.plan_id = p.id AND fe.feature_key = 'FYN_INSIGHTS');
