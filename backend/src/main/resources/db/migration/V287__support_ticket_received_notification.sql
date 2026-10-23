-- Acknowledgement email sent when a customer raises a support ticket (SupportTicketService.create).
-- EMAIL only: the customer asked for an email, and a push for "we got your message" is noise on a
-- screen that already confirms the ticket was created. {{ticketNumber}} is the only placeholder --
-- the ticket's subject and description are the customer's own words and stay out of the email.
-- No promised response time: nothing in the product commits to one for tickets, so the copy does not.
--
-- The email itself is the branded one ResendEmailProvider.buildSupportTicketReceivedMessage builds
-- (a "View your request" button, and the standard "just reply to this email" footer). This row is
-- what the notification record and the admin portal show, so it carries the whole message the
-- customer read, footer sentence included. SupportTicketReceivedCopyIT fails if the two drift apart.
INSERT INTO notification_templates (id, type, channel, title_template, body_template) VALUES
    (gen_random_uuid(), 'SUPPORT_TICKET_RECEIVED', 'EMAIL',
     'We received your support request {{ticketNumber}}',
     'Thanks for contacting Fynora support. We have received your request, reference {{ticketNumber}}, '
     'and our team is working on it. We will get back to you by email as soon as we have an update. '
     'Questions? Just reply to this email and it''ll reach us.');
