# Admin sidebar grouped by job

Status: approved by Sid, 2026-11-17 ("go with the proposed grouping, build it"), from an interactive
side-by-side of today's sidebar and this grouping.

## Why

The admin sidebar had 40 links in five groups (Core, Intelligence, Governance, Operations, System) that
did not match how the pages are used. System alone held 17 unrelated pages: statement import tools,
server health, messages to customers and Settings. Merchant pages were split across Core and
Intelligence.

## What

Dashboard and Needs a decision stay on top, outside any group. Every other page sits under the job it is
for:

| Group | Pages |
|---|---|
| Customers | Users, Subscriptions, Billing Offers, Referrals, Support Tickets, Feedback |
| Statements and imports | Held Imports, Held Statements, Import Health, Reading quality by bank, Import Trace, Import Row Trace, Layout Intelligence, Layout Studio, Trust Review Metrics, Banks |
| Merchants and categories | Merchant Intelligence, Merchant Templates, Merchant Review, Global Rules, Learning Engine, Learning Queue, Trusted Senders |
| Numbers | Platform Analytics, Activation, Insight Explorer, Reconciliation Monitor, Reconciliation Explorer |
| Messages to customers | Notifications, Push Campaigns, Festival App Icon |
| Server health | System Health, Background jobs, Integrations, Platform Diagnostics |
| Admin and settings | Roles & Permissions, Audit Log, Settings |

Unchanged: every page's route, icon, permission and highlight rule; groups still collapse, and a
collapsed group is still remembered per browser.

## Approaches

- **Group by job (chosen).** Each group answers "what am I here to do?", so the busy System group
  splits along the lines that were already visible in it.
- **Order by how often each page is opened.** Sid was offered marking weekly and rarely-used pages
  first; he chose the job grouping as shown instead. No usage data per admin page exists to sort by.
- **Keep the five groups and only move pages.** Leaves the System problem in place.

## Calls made while building (Claude's, Sid can change them)

- Group names and page order exactly as in the approved side-by-side.
- At the old header letter spacing (`tracking-widest`), "Merchants and categories" wrapped onto two
  lines in the 256px sidebar (measured in the browser). Headers now use `tracking-wide`: every name fits
  on one line with at least 10px before its chevron, and a `gap-2` keeps text and chevron apart if a
  future name wraps. The phone drawer is the same 256px sidebar.
- A collapsed group saved under an old name (Core, System...) matches nothing, so every group starts
  open once; those old names are dropped from the browser's saved list the next time it is saved.
