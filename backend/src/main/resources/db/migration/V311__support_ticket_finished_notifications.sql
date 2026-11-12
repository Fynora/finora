-- Telling the customer when an admin resolves or closes their support ticket (2026-11-12).
--
-- Two types, because a template is one row per (type, channel) and the two outcomes say different
-- things: RESOLVED means we fixed or answered it; CLOSED means we are closing it without a fix (no
-- reply, out of scope, spam). EMAIL only, like the acknowledgement (V287) and the duplicate notice
-- (V288): the customer already sees the status in the app, and a push for it would be noise.
--
-- The email itself is built by ResendEmailProvider.buildSupportTicketFinishedMessage, which can
-- include the admin's optional message; these rows are the stored title and plain body every
-- notification keeps. Their one param is {{ticketNumber}}. Never the ticket's subject or
-- description (the customer's own words), and never the admin's message: an unmatched placeholder
-- would show literally when there is none, and the message travels in the notification's params.
-- No promised response time: nothing in the product commits to one.
INSERT INTO notification_templates (id, type, channel, title_template, body_template) VALUES
    (gen_random_uuid(), 'SUPPORT_TICKET_RESOLVED', 'EMAIL',
     'Your support request {{ticketNumber}} is resolved',
     'We have resolved your support request {{ticketNumber}}. If something is still not right, '
     'reply to this email and mention your reference.'),
    (gen_random_uuid(), 'SUPPORT_TICKET_CLOSED', 'EMAIL',
     'Your support request {{ticketNumber}} is closed',
     'We have closed your support request {{ticketNumber}}. If you still need help with it, '
     'reply to this email and mention your reference.');
