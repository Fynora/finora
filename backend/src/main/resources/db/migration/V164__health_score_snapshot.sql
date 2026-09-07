CREATE TABLE health_score_snapshot (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    year_month                VARCHAR(7) NOT NULL,
    overall_score             INT NOT NULL,
    label                     VARCHAR(32) NOT NULL,
    savings_rate_score        DOUBLE PRECISION NOT NULL,
    debt_score                DOUBLE PRECISION NOT NULL,
    emergency_fund_score      DOUBLE PRECISION NOT NULL,
    spend_consistency_score   DOUBLE PRECISION NOT NULL,
    cash_flow_stability_score DOUBLE PRECISION NOT NULL,
    computed_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, year_month)
);
