-- AI-created categories -- docs/superpowers/specs/2026-09-15-ai-category-creation-design.md.
--
-- Two-tier AI resolution: merchant_understanding is global (what kind of merchant is this),
-- shared across every user; user_merchant_category_resolution is per-user (which of THIS
-- user's categories it maps to, or what a new one should be called). Reusing one cached
-- category name across users was the bug this design fixes -- category naming is inherently
-- per-user, merchant understanding is not.

ALTER TABLE categories ADD COLUMN ai_creation_reason TEXT;

CREATE TABLE merchant_understanding (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    counterparty_key  VARCHAR(255) NOT NULL,
    direction         VARCHAR(10) NOT NULL,
    understanding     TEXT NOT NULL,
    model             VARCHAR(64) NOT NULL,
    generated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_merchant_understanding_key_direction UNIQUE (counterparty_key, direction)
);

CREATE TABLE user_merchant_category_resolution (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL,
    counterparty_key  VARCHAR(255) NOT NULL,
    direction         VARCHAR(10) NOT NULL,
    category_id       UUID NOT NULL REFERENCES categories(id),
    resolved_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_merchant_resolution_user_key_direction
        UNIQUE (user_id, counterparty_key, direction)
);

-- CategoryService.delete's dependency check (Task 6): "does this category have any resolutions
-- pointing at it" needs to be cheap, same reasoning as every other dependent lookup there.
CREATE INDEX idx_user_merchant_resolution_category
    ON user_merchant_category_resolution (category_id);
