-- Shared merchant category corpus -- docs/superpowers/specs/2026-09-15-shared-merchant-corpus-design.md.
--
-- Three tables, deliberately separate (spec §4):
--   counterparty_category_observation: private, append-only log of human corrections. Never
--     read by the categorization waterfall.
--   shared_merchant_category: the actual corpus. One row per (counterparty_key, direction),
--     created only by promotion once >=3 distinct human voters corroborate a mapping.
--   shared_merchant_category_ai_suggestion: latest AI answer per key, upserted, never evidence.
--
-- direction reuses Transaction.Type's own value set (INCOME/EXPENSE) rather than inventing a
-- new DEBIT/CREDIT enum -- same column width as txn_type (V1__init_schema.sql).
-- counterparty_type_at_vote reuses CounterpartyType's value set, same width as
-- transactions.counterparty_type (V142__transaction_counterparty.sql).

CREATE TABLE counterparty_category_observation (
    id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    counterparty_key           VARCHAR(255) NOT NULL,
    direction                  VARCHAR(10) NOT NULL,
    category                   VARCHAR(80) NOT NULL,
    user_id                    UUID NOT NULL,
    counterparty_type_at_vote  VARCHAR(24) NOT NULL,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The only access pattern Task 2's promotion logic uses: every observation for one key+direction.
CREATE INDEX idx_observation_key_direction
    ON counterparty_category_observation (counterparty_key, direction);

-- Task 6's retention sweep: find keys whose newest observation is older than a cutoff.
CREATE INDEX idx_observation_key_direction_created_at
    ON counterparty_category_observation (counterparty_key, direction, created_at);

CREATE TABLE shared_merchant_category (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    counterparty_key      VARCHAR(255) NOT NULL,
    direction             VARCHAR(10) NOT NULL,
    status                VARCHAR(16) NOT NULL,
    category              VARCHAR(80) NOT NULL,
    category_distribution JSONB NOT NULL,
    distinct_user_count   INT NOT NULL,
    promoted_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_recomputed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    revalidating_since    TIMESTAMPTZ,
    CONSTRAINT uq_shared_merchant_category_key_direction UNIQUE (counterparty_key, direction)
);

-- The waterfall's only read: "is there a TRUSTED row for this key+direction". Partial index
-- keeps it small -- Disputed/Revalidating rows are never read by the waterfall (spec §9).
CREATE INDEX idx_shared_merchant_category_trusted
    ON shared_merchant_category (counterparty_key, direction)
    WHERE status = 'TRUSTED';

CREATE TABLE shared_merchant_category_ai_suggestion (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    counterparty_key  VARCHAR(255) NOT NULL,
    direction         VARCHAR(10) NOT NULL,
    category          VARCHAR(80) NOT NULL,
    model             VARCHAR(64) NOT NULL,
    generated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_shared_merchant_category_ai_suggestion_key_direction UNIQUE (counterparty_key, direction)
);
