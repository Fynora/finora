-- Shared payments and loans (docs/superpowers/specs/2026-10-18-shared-payments-and-loans-design.md).
-- Everything here is something the user confirmed; nothing in these tables changes a figure without
-- them. Rules are applied when figures are read (PeopleLedger), never copied into share_links, so a
-- rule covers past months and removing it undoes it.
--
-- ON DELETE CASCADE to users as insight_acknowledgements: the user's own answers, not an audit trail.
-- (The account purge anonymizes the users row rather than deleting it, so it also deletes these rows
-- explicitly -- see PeopleRepository.deleteByUserId.) ON DELETE CASCADE to transactions too: a hard
-- delete of a row takes its links with it. A soft-deleted, duplicate or superseded row is left in
-- place here and ignored by PeopleLedger.

-- A credit that is someone's share of a bill (an expense row). One credit can cover several bills,
-- and a bill can have several shares. amount is what the user confirmed, capped when read.
CREATE TABLE share_links (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    credit_txn_id UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    bill_txn_id UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    amount NUMERIC(15,2) NOT NULL CHECK (amount > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (credit_txn_id, bill_txn_id)
);
CREATE INDEX idx_share_links_user_id ON share_links(user_id);

-- "Shares from this sender against this bill, every time." The bill is named by its category plus
-- its payee key, or its merchant name when the payee key names no one. Never by amount.
CREATE TABLE split_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sender_key VARCHAR(200) NOT NULL,
    bill_category_id UUID NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    bill_payee_key VARCHAR(200),
    bill_merchant VARCHAR(300),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at TIMESTAMPTZ,
    CHECK (bill_payee_key IS NOT NULL OR bill_merchant IS NOT NULL)
);
CREATE INDEX idx_split_rules_user_id ON split_rules(user_id);

-- One line of a person's balance. txn_id is null for CASH_SETTLED and for a LET_GO of a balance;
-- a LET_GO naming a LENT row turns that row back into spending.
CREATE TABLE loan_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    person_key VARCHAR(200) NOT NULL,
    txn_id UUID REFERENCES transactions(id) ON DELETE CASCADE,
    direction VARCHAR(20) NOT NULL CHECK (direction IN ('LENT', 'PAID_BACK', 'BORROWED', 'REPAID', 'CASH_SETTLED', 'LET_GO')),
    amount NUMERIC(15,2) NOT NULL CHECK (amount > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (txn_id, direction)
);
CREATE INDEX idx_loan_entries_user_person ON loan_entries(user_id, person_key);

-- Per-person choices. family: never asked "lent?" or "settle it" for this person.
CREATE TABLE person_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    person_key VARCHAR(200) NOT NULL,
    family BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (user_id, person_key)
);

-- The user's own UPI id, asked once for "Ask them": the payment link it builds pays the user.
CREATE TABLE people_preferences (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    own_upi_id VARCHAR(100),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- "Got it" on the new Insights suggestions. Keys:
--   SHARE_CHECK / LENT_CHECK / PAYBACK_CHECK  "<transaction id>"
--   SETTLE         "<person key>|<newest loan entry id>"  (asks again once the balance changes)
--   EVERY_MONTH    "<sender key>|<bill category id>"
--   ALWAYS_PERSON  "<person key>"
ALTER TABLE insight_acknowledgements DROP CONSTRAINT insight_acknowledgements_kind_check;
ALTER TABLE insight_acknowledgements ADD CONSTRAINT insight_acknowledgements_kind_check CHECK (kind IN (
    'CATEGORY_HIGH', 'AMOUNT_CHANGED', 'CARD_STATEMENT_MISSING',
    'SHARE_CHECK', 'LENT_CHECK', 'PAYBACK_CHECK', 'SETTLE', 'EVERY_MONTH', 'ALWAYS_PERSON'));
