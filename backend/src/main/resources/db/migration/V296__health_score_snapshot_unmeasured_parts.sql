-- A health score part that cannot be measured yet is left out of the score and stored as NULL:
-- Spend Consistency needs two full calendar months, Cash Flow Stability one, the Savings Rate
-- income that is counted and mostly classified, the Emergency Fund recorded expenses. They used to
-- score 100 (0 for the savings rate) on no data -- measured in production on 2026-10-30, 5 of 12
-- users' scores moved by it, one from "Good" to what its measured parts say is "Fair". Debt Score is
-- always measured (no card is no debt) and stays NOT NULL.
ALTER TABLE health_score_snapshot ALTER COLUMN savings_rate_score DROP NOT NULL;
ALTER TABLE health_score_snapshot ALTER COLUMN emergency_fund_score DROP NOT NULL;
ALTER TABLE health_score_snapshot ALTER COLUMN spend_consistency_score DROP NOT NULL;
ALTER TABLE health_score_snapshot ALTER COLUMN cash_flow_stability_score DROP NOT NULL;
