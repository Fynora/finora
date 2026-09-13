-- Fyn (docs/superpowers/specs/2026-09-13-fino-ai-implementation-plan.md, §6 Phase 1) foundation
-- schema for Phase 4's chat Q&A. Created here, ahead of Phase 4, so Phase 1 ships the full schema
-- once rather than each phase adding its own slice.
CREATE TABLE chat_conversations (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL,
    title       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_conversations_user_updated ON chat_conversations(user_id, updated_at DESC);

-- feedback (plan §4.6, second review round): nullable, set by the user tapping thumbs up/down on an
-- assistant message. Only ever meaningful on role = 'ASSISTANT' rows -- not constrained in SQL
-- because Postgres CHECK constraints referencing another column of the same row on a nullable field
-- add more friction than the invariant is worth here; enforced at the service layer instead, same
-- as this repo's existing convention for role-conditional columns.
CREATE TABLE chat_messages (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id   UUID NOT NULL,
    role              VARCHAR(20) NOT NULL,
    content           TEXT NOT NULL,
    tool_calls_json   JSONB,
    feedback          VARCHAR(20),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_chat_messages_conversation_created ON chat_messages(conversation_id, created_at);
