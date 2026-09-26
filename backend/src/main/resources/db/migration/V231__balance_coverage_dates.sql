-- Statements imported out of month order.
--
-- Account.balance already holds every transaction up to some date: the period end of the
-- statement whose closing balance last SET it, or -- for an account created by an import -- the
-- day before that first statement's period, since its opening balance is where the account
-- started. An older statement imported afterwards used to add its rows on top anyway, counting
-- them twice. These two columns let the importer (and every later reversal) tell which rows were
-- already inside the balance.
--
-- accounts.balance_baseline_date: the date the account's starting balance was "as of", stamped
-- when an import creates the account with a stated opening balance. NULL for every existing
-- account and every account created by hand: nothing is known, and nothing changes for them.
--
-- statement_imports.balance_covered_through: this statement's rows dated on or before this day
-- did not move Account.balance when it was imported, because the balance already held them.
-- NULL for every existing statement (they were imported under the old rule, and their recorded
-- balance_application_mode still says exactly what they did).
ALTER TABLE accounts ADD COLUMN balance_baseline_date DATE;
ALTER TABLE statement_imports ADD COLUMN balance_covered_through DATE;
