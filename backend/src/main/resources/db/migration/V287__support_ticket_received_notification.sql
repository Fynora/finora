-- Acknowledgement email sent when a customer raises a support ticket (SupportTicketService.create).
-- EMAIL only: the customer asked for an email, and a push for "we got your message" is noise on a
-- screen that already confirms the ticket was created. {{ticketNumber}} is the only param -- the
-- ticket's subject and description are the customer's own words and stay out of the email.
-- No promised response time: nothing in the product commits to one for tickets, so the copy does not.
INSERT INTO notification_templates (id, type, channel, title_template, body_template) VALUES
    (gen_random_uuid(), 'SUPPORT_TICKET_RECEIVED', 'EMAIL',
     'We received your support request {{ticketNumber}}',
     'Thanks for contacting Fynora support. We have received your request, reference {{ticketNumber}}, '
     'and our team is working on it. We will get back to you by email as soon as we have an update. '
     'If you have more to add, reply to this email and mention your reference.');
