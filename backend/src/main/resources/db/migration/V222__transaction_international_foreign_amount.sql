-- Whether a transaction was an international spend, as its own statement printed it, and the
-- original-currency amount printed beside the rupee amount when there was one. A real HDFC
-- credit-card statement splits its ledger under "Domestic Transactions" and "International
-- Transactions" headings and prints "USD 12.50" next to the rupee amount billed for a foreign
-- purchase -- see PdfTableLocator.TRANSACTION_REGION_HEADING and CsvParser.ForeignCurrencyPrefix.
--
-- international is NOT NULL DEFAULT FALSE, so every existing row backfills to "not marked
-- international" -- which is what those rows were: nothing before this ever read the heading.
-- foreign_currency/foreign_amount are nullable and set together or not at all; the GST and
-- FX-markup rows of an international table carry no foreign amount of their own. amount stays the
-- rupee amount billed; foreign_amount is display data about it, never a second amount to sum.
ALTER TABLE transactions ADD COLUMN international BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE transactions ADD COLUMN foreign_currency VARCHAR(3);
ALTER TABLE transactions ADD COLUMN foreign_amount NUMERIC(14,2);
ALTER TABLE transactions ADD CONSTRAINT chk_transactions_foreign_amount_pair
    CHECK ((foreign_currency IS NULL) = (foreign_amount IS NULL));
