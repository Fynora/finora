-- Trust the sender domains Swiggy, Instamart and Amazon Pay actually send from.
--
-- WHY
-- ---
-- V103 trusted `swiggy.com` for Swiggy, but real Swiggy mail is authenticated as `swiggy.in`.
-- Checked on real mail: Gmail's own Authentication-Results carried `dmarc=pass header.from=swiggy.in`
-- (and `dkim=pass header.i=@swiggy.in`) for a Swiggy order email, `header.from=instamart.in` for an
-- Instamart order email, and `header.from=amazonpay.in` for an Amazon Pay payment email.
-- SenderAuthenticationService matches the AUTHENTICATED domain exactly, so `swiggy.com` on the
-- registry never matched any of this mail, and neither a template nor a parser for these merchants
-- could ever have been reached: the message was refused at the trust gate as DOMAIN_NOT_TRUSTED.
--
-- WHAT THIS DOES, AND DOES NOT
-- ----------------------------
-- Trusting a domain only lets Finora EXAMINE mail Gmail has already authenticated as that domain.
-- It stages nothing on its own: with no enabled template and no parser for the domain, a message
-- is left DETECTED_NOT_STAGED and its body is never fetched (GmailReceiptExtractionService only
-- fetches a body once a parser claims the domain). Reading receipts from these three still needs a
-- template (admin Merchant Templates page, tested against a real sample before activation) or a
-- parser; this only removes the gate that made that impossible.
--
-- Exact domains only, matched exactly: no subdomain and no suffix rule (see V82). A sender that
-- uses a subdomain gets its own row when one is actually observed.
--
-- ON CONFLICT DO NOTHING: an admin can add a domain through the management endpoints, and there is
-- a unique index on `domain`. If a row already exists here, including one an admin deliberately
-- DISABLED, it is left exactly as it is -- this migration must neither fail the boot nor quietly
-- re-enable a domain someone decided to stop trusting.
INSERT INTO gmail_trusted_sender_domains (id, domain, merchant_name, status) VALUES
    (gen_random_uuid(), 'swiggy.in',     'Swiggy',     'ACTIVE'),
    (gen_random_uuid(), 'instamart.in',  'Instamart',  'ACTIVE'),
    (gen_random_uuid(), 'amazonpay.in',  'Amazon Pay', 'ACTIVE')
ON CONFLICT (domain) DO NOTHING;
