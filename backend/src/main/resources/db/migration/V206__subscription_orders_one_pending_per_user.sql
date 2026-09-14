-- Closes a checkout() double-submit race: two concurrent first-time checkouts for a user with no
-- existing subscription/order both pass the check-then-act PENDING-order guard in
-- BillingCheckoutService.resumableOrderOrGuard() before either INSERT commits, each creating a
-- real, separate Razorpay subscription. idx_subscriptions_one_active_per_user (V99) only guards
-- once a webhook activates a row -- nothing DB-enforced blocks the race at checkout time itself.
CREATE UNIQUE INDEX idx_subscription_orders_one_pending_per_user ON subscription_orders(user_id)
    WHERE status = 'PENDING';
