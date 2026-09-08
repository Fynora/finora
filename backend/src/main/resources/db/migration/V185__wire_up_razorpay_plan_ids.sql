-- One-time Razorpay-account setup step V154's own migration comment described:
-- "razorpay_plan_id stays NULL until [this runs] -- checkout refuses with a clear error until
-- then." Plan objects were created in the live Razorpay Dashboard (screenshot shared 2026-09-08)
-- at the same amounts already seeded here -- these are still the GST-EXCLUSIVE amounts
-- (₹399/₹799/₹3,500/₹8,000); see InvoiceService's own class doc for why the invoice PDF still
-- itemizes GST as a post-hoc split of that same charged total, not a real additional amount,
-- until these Razorpay plans are repriced (Razorpay Subscriptions plans have no separate tax
-- field -- confirmed against Razorpay's own Create Plan API docs -- so a real GST charge means
-- repricing item.amount to the GST-inclusive total, not adding a tax parameter).

UPDATE billing_prices SET razorpay_plan_id = 'plan_TYgEidywnYfCIM'
    WHERE plan_id = (SELECT id FROM plans WHERE code = 'PLUS') AND billing_cycle = 'MONTHLY';
UPDATE billing_prices SET razorpay_plan_id = 'plan_TYgFh62Y5hbRMn'
    WHERE plan_id = (SELECT id FROM plans WHERE code = 'PLUS') AND billing_cycle = 'YEARLY';
UPDATE billing_prices SET razorpay_plan_id = 'plan_TYgGAQzik1ipEF'
    WHERE plan_id = (SELECT id FROM plans WHERE code = 'PREMIUM') AND billing_cycle = 'MONTHLY';
UPDATE billing_prices SET razorpay_plan_id = 'plan_TYgGx8cjqcJsuu'
    WHERE plan_id = (SELECT id FROM plans WHERE code = 'PREMIUM') AND billing_cycle = 'YEARLY';
