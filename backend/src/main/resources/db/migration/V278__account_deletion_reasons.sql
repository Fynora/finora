-- Gate 2a spec §5: the optional reason someone gives when they delete their account. Written in the
-- same transaction that marks the account for deletion, so a reason exists only for a deletion that
-- happened.
--
-- Deliberately unlinked from the person: no user_id, no foreign key, no email, and no date finer than
-- the month (deletion_month is always the first day of the month in app.platform.reporting-zone). At
-- today's small numbers a day, or a timestamp, would let a row be matched to the deletion's own audit
-- entry. Once unlinked it is not personal data, so it is kept after the account purge; the privacy
-- page says so.
--
-- had_data: whether the person had ever imported a statement, the one fact the activation page needs
-- to tell "never got started" from "used it and left".
CREATE TABLE account_deletion_reasons (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reason VARCHAR(32) NOT NULL
        CONSTRAINT account_deletion_reasons_reason_valid
        CHECK (reason IN (
            'COULDNT_GET_STATEMENTS_IN', 'NOT_COMFORTABLE_WITH_DATA', 'NOTHING_USEFUL',
            'USE_ANOTHER_APP', 'DONT_NEED_IT', 'SOMETHING_ELSE')),
    deletion_month DATE NOT NULL
        CONSTRAINT account_deletion_reasons_month_only
        CHECK (EXTRACT(DAY FROM deletion_month) = 1),
    had_data BOOLEAN NOT NULL
);
-- For the activation page's count per reason per month.
CREATE INDEX idx_account_deletion_reasons_month ON account_deletion_reasons(deletion_month);
