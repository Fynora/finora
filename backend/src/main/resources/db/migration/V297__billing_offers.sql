-- Time-boxed first-period offers on a paid plan. The table is general: any number of offers, past,
-- live and upcoming, one row per plan and billing cycle. The first one, Diwali 2026, is seeded at
-- the bottom (owner decision, 2026-10-30: Plus at half price for the first month or first year,
-- ending 7 Nov 2026); later offers are meant to be added from an admin page, not a migration.
--
-- How an offer is charged: checkout creates the normal Razorpay subscription on the plan's usual
-- Razorpay plan, with the offer price as a one-time upfront amount and the first regular charge
-- moved one billing period out (start_at). The customer pays the offer price today and the regular
-- price from the next period on. Proven in Razorpay test mode on 2026-10-30, by UPI and by card.
-- Razorpay's own Offers feature was tried first and rejected: one offer covers either cards or UPI,
-- never both, and a subscription carries a single offer.
--
-- first_period_price is the full amount charged for the first period, GST included, like
-- billing_prices.price. An offer is live while starts_at <= now < ends_at; nothing deletes rows, so
-- the table is also the record of past offers.
CREATE TABLE billing_offers (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                VARCHAR(40) NOT NULL,
    plan_id             UUID NOT NULL REFERENCES plans(id),
    billing_cycle       VARCHAR(10) NOT NULL,
    first_period_price  NUMERIC(10, 2) NOT NULL CHECK (first_period_price > 0),
    label               VARCHAR(80) NOT NULL,
    starts_at           TIMESTAMPTZ NOT NULL,
    ends_at             TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at),
    UNIQUE (code, plan_id, billing_cycle)
);
CREATE INDEX idx_billing_offers_plan_cycle ON billing_offers(plan_id, billing_cycle);

-- Which offer, if any, an order was checked out under. The webhook reads it to know the first
-- payment is an upfront offer payment, and checkout reads it so a pending order is only resumed
-- when it was made under the offer that applies now.
ALTER TABLE subscription_orders ADD COLUMN offer_code VARCHAR(40);

-- Diwali 2026: Rs 125 for the first month (Rs 249 after), Rs 999 for the first year (Rs 1,999
-- after). Live from deploy until the end of 7 Nov 2026, India time.
INSERT INTO billing_offers (code, plan_id, billing_cycle, first_period_price, label, starts_at, ends_at)
    SELECT 'DIWALI2026', id, 'MONTHLY', 125.00, 'Diwali offer',
           TIMESTAMPTZ '2026-10-30 00:00:00+05:30', TIMESTAMPTZ '2026-11-08 00:00:00+05:30'
    FROM plans WHERE code = 'PLUS';
INSERT INTO billing_offers (code, plan_id, billing_cycle, first_period_price, label, starts_at, ends_at)
    SELECT 'DIWALI2026', id, 'YEARLY', 999.00, 'Diwali offer',
           TIMESTAMPTZ '2026-10-30 00:00:00+05:30', TIMESTAMPTZ '2026-11-08 00:00:00+05:30'
    FROM plans WHERE code = 'PLUS';
