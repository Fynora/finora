-- Maps the Google Play subscription created in Play Console on 2026-10-25 (subscription "plus",
-- base plan "monthly", India only) to the Plus plan, monthly cycle.
--
-- RevenueCat's webhook sends Google Play products set up after February 2023 as
-- "<subscription_id>:<base_plan_id>", so the product_id that arrives is "plus:monthly".
-- RevenueCatWebhookDispatcher.handleInitialPurchase looks the product up here by
-- (provider_product_id, platform); without a row it logs a warning and ignores the purchase, so a
-- buyer would be charged by Google and stay on Free.
--
-- ON CONFLICT: a row with this key may already have been added by hand in some environment; the
-- unique (provider_product_id, platform) constraint would otherwise stop the migration.
INSERT INTO iap_products (provider_product_id, plan_id, billing_cycle, platform)
SELECT 'plus:monthly', id, 'MONTHLY', 'ANDROID' FROM plans WHERE code = 'PLUS'
ON CONFLICT (provider_product_id, platform) DO NOTHING;
