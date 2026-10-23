-- An admin can mark a ticket as a duplicate of another ticket the same customer raised.
--
-- Marking a duplicate closes it (SupportTicket.Status already says "OPEN may go straight to CLOSED
-- so spam and duplicates close in one step") and records which ticket it duplicates. The customer is
-- emailed once, using the template below.
--
-- duplicate_of_ticket_id carries the integrity: a real foreign key. ON DELETE SET NULL, never
-- CASCADE: if the reference row is ever removed the duplicate must survive as a plain closed ticket,
-- not vanish with it. (Account deletion removes both rows in one statement, which a foreign key
-- accepts.)
--
-- duplicate_of_ticket_number is a deliberate copy of the reference's ticket_number. Ticket numbers
-- are unique and never change, so the copy cannot drift, and it lets every list and detail read show
-- "duplicate of SUP-000042" without a join or a second query per row. It is written in the same
-- statement as the id, by the one service method that sets either.
ALTER TABLE support_tickets
    ADD COLUMN duplicate_of_ticket_id     UUID REFERENCES support_tickets(id) ON DELETE SET NULL,
    ADD COLUMN duplicate_of_ticket_number VARCHAR(32);

-- Partial: almost every ticket is not a duplicate, and the only read this serves is "which tickets
-- point at this one".
CREATE INDEX idx_support_tickets_duplicate_of ON support_tickets (duplicate_of_ticket_id)
    WHERE duplicate_of_ticket_id IS NOT NULL;

COMMENT ON COLUMN support_tickets.duplicate_of_ticket_id IS
    'The ticket this one was closed as a duplicate of, set by an admin (SupportTicketDuplicateService). '
    'Always a ticket of the same customer, and never itself a duplicate, so there are no chains.';

COMMENT ON COLUMN support_tickets.duplicate_of_ticket_number IS
    'Copy of the reference ticket''s ticket_number, written with duplicate_of_ticket_id. See V288.';

-- The email to the customer. EMAIL only, like the ticket acknowledgement: the customer already sees
-- the ticket closed in the app, and a push for it would be noise. Both params are ticket numbers;
-- the ticket's subject and description are the customer's own words and stay out of the email.
-- No promised response time: nothing in the product commits to one for tickets.
INSERT INTO notification_templates (id, type, channel, title_template, body_template) VALUES
    (gen_random_uuid(), 'SUPPORT_TICKET_DUPLICATE', 'EMAIL',
     'Your support request {{ticketNumber}} is a duplicate',
     'We have closed {{ticketNumber}} as a duplicate of {{referenceNumber}}, which you raised earlier, '
     'so that everything about this stays in one place. You can find {{referenceNumber}} in the Fynora '
     'app; you do not need to raise it again. If {{ticketNumber}} was about something '
     'different, reply to this email and mention your reference.');
-- Deliberately does not say "follow it for updates": the reference may already be resolved or closed,
-- and an email that promises updates which will never come is worse than saying nothing.
