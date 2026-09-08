-- Payment Method card on the Billing page (Razorpay-backed subscriptions only). Razorpay's own
-- subscription.activated/subscription.charged webhooks already carry a payment.entity.card object
-- (last4/network/type) for every card-authorized subscription -- confirmed against Razorpay's docs,
-- not guessed -- so this only needs storage for what RazorpayWebhookDispatcher extracts from a
-- payload it already receives, never a new API call or any raw card number/CVV.
ALTER TABLE subscriptions
    ADD COLUMN card_last4 VARCHAR(4),
    ADD COLUMN card_network VARCHAR(20),
    ADD COLUMN card_type VARCHAR(20);
