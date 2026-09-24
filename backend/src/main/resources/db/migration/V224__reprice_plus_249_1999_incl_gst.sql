-- Plus repriced (owner decision, 2026-09-24): Rs 249/month and Rs 1,999/year, INCLUDING 18% GST.
-- The LLP is GST-registered; the amount below is exactly what the Razorpay plan charges, and the
-- invoice PDF splits the GST out of it (InvoiceService). Razorpay plans have no separate tax
-- field and cannot be edited (see V185), so the new amounts are new Razorpay plans, created in the
-- live Razorpay Dashboard on 2026-09-24:
--   plan_TfqXw7GNqwcUt6  Fynora Plus (Monthly)  Rs 249.00   every month
--   plan_TfqYQovys3Dsg6  Fynora Plus (Yearly)   Rs 1,999.00 every year
--
-- The old Rs 399 / Rs 3,500 rows are deactivated, not updated or deleted: new checkouts pick the
-- active row (findByPlanIdAndBillingCycleAndActiveTrue), while a webhook for a subscription still
-- on an old Razorpay plan keeps resolving through RazorpayWebhookDispatcher's lookup over ALL
-- rows by razorpay_plan_id. Deactivate first -- idx_billing_prices_plan_cycle_active allows one
-- active row per (plan, cycle).
UPDATE billing_prices SET active = false, updated_at = now()
    WHERE plan_id = (SELECT id FROM plans WHERE code = 'PLUS') AND active;

INSERT INTO billing_prices (plan_id, billing_cycle, price, currency, razorpay_plan_id)
    SELECT id, 'MONTHLY', 249.00, 'INR', 'plan_TfqXw7GNqwcUt6' FROM plans WHERE code = 'PLUS';
INSERT INTO billing_prices (plan_id, billing_cycle, price, currency, razorpay_plan_id)
    SELECT id, 'YEARLY', 1999.00, 'INR', 'plan_TfqYQovys3Dsg6' FROM plans WHERE code = 'PLUS';
