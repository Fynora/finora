-- Inflow kinds (Plan 2 of the financial flow program; spec
-- docs/superpowers/specs/2026-09-27-inflow-kinds-design.md).
--
-- A user says what a credit Fynora could not explain actually was: one of five built-in kinds or
-- one of their own. A kind either counts as income or does not. A choice is remembered for the
-- sender (sender_inflow_rules, keyed on transactions.counterparty_key) or for one row
-- (transactions.inflow_kind_id). FlowClassifier reads both at read time; nothing here is a cache.
CREATE TABLE inflow_kinds (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES users(id),
    name             VARCHAR(60) NOT NULL,
    counts_as_income BOOLEAN NOT NULL,
    built_in         VARCHAR(20),               -- INCOME | FAMILY_SUPPORT | OWN_MONEY | PAID_BACK | REFUND
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One of each built-in per user, so two parallel first requests cannot create a second set.
CREATE UNIQUE INDEX uq_inflow_kinds_user_built_in ON inflow_kinds(user_id, built_in) WHERE built_in IS NOT NULL;
CREATE UNIQUE INDEX uq_inflow_kinds_user_name ON inflow_kinds(user_id, lower(name));

-- counterparty_key matches transactions.counterparty_key (V142).
CREATE TABLE sender_inflow_rules (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES users(id),
    counterparty_key VARCHAR(120) NOT NULL,
    inflow_kind_id   UUID NOT NULL REFERENCES inflow_kinds(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_sender_inflow_rules_user_key UNIQUE (user_id, counterparty_key)
);

CREATE INDEX idx_sender_inflow_rules_kind ON sender_inflow_rules(inflow_kind_id);

ALTER TABLE transactions ADD COLUMN inflow_kind_id UUID REFERENCES inflow_kinds(id);
CREATE INDEX idx_transactions_inflow_kind ON transactions(inflow_kind_id) WHERE inflow_kind_id IS NOT NULL;
