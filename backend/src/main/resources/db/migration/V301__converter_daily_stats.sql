-- The free statement converter (design 2026-10-31, approved by Sid): how many conversions worked or
-- failed, per day. Counts only. Nothing here identifies a visitor or a statement: no IP address, no
-- file name, no content hash, no amount, no date or text from any statement. A bank name and a
-- band of row counts identify nobody.
--
-- The converter keeps nothing else: the file and its transactions exist only for the request.
CREATE TABLE converter_daily_stats (
    day          DATE        NOT NULL,           -- India's calendar day
    outcome      VARCHAR(40) NOT NULL,           -- CONVERTED, NOT_CONFIRMED, or the refusal's error code
    bank         VARCHAR(40) NOT NULL,           -- BankRegistry id as detected, or '-' when none was read
    row_band     VARCHAR(10) NOT NULL,           -- '0', '1-50', '51-200', '201+'
    conversions  INTEGER     NOT NULL DEFAULT 0,
    PRIMARY KEY (day, outcome, bank, row_band)
);
