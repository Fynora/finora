# Website Insights redesign

Status: approved by Sid in chat, 2026-10-24, from two mockups ("redraw with all five", then "yes build it").
The website follow-up that `2026-10-16-mobile-insights-redesign-design.md` promised ("Mobile first; web
later"). That spec's goals, anti-goals and constraints apply here too; this file records only what differs
on the website.

## Layout

On a wide screen (Tailwind `lg`, 1024 px and up) there are two columns:
- **Left, what to act on:**
  - the month line, with the AI month story and "View the numbers";
  - the top merchant line;
  - "To look at".
- **Right, what to glance at:**
  - "Coming up";
  - "Paid regularly";
  - "With people".

Below `lg` it is one column in that order, with the question links under the month line, as on the
phone.

A footer holds "See the full month in Reports" and the one-line note saying which text is AI and which is
rule-based. The note replaces today's banner at the top.

## Sections

- **Month line.** The same sentence the phone builds from Reports' trend for the reporting month.
- **To look at.** The server's rows (`GET /insights/look-at`, AI insights spec §8.2), in its order:
  - card statement missing, with Upload (`/app/import`);
  - people questions, through the website's existing `PeopleQuestion`;
  - a new repeating payment, through `RecurringQuestion`;
  - an amount changed, through `RecurringQuestion` in its amount-changed form, plus "Got it";
  - running high, linking to the Ledger filtered by category and month.

  There is no "Why?" on the website (AI insights spec §11.8).
  - When the list fails to load: "Couldn't load what needs a look." with Try again. The website never built
    this list itself, so there is no local fallback.
  - When the list is empty: "Nothing new to look at for September 2026."
  - When the user has data for one month only: "Nothing to compare September 2026 with yet. Add the
    month before and Fynora can tell you what changed.", with "Upload last month's statement".
- **"Got it" with undo.** Clicking hides the row at once and shows "Hidden: <row> · Undo" in its place for
  5 seconds. The acknowledgement is only sent when that time ends, so Undo needs no server change.
  - Undo brings the row back and sends nothing.
  - Leaving the page sends any pending acknowledgement at once, so a "Got it" is never lost.
- **Coming up.** The phone's `comingUp` rule:
  - soonest first;
  - a past date reads "expected around 1 Oct", is greyed, and is left out of "Still to go out this month";
  - the latest-statement note.

  "Still to go out this month" is the section's large figure.
- **Paid regularly.**
  - The yearly total is the large figure, with the monthly total under it.
  - A shared bill counts at the user's share.
  - A rise is marked.
  - The existing "Yes, keep tracking this" and "Not recurring" buttons stay.
- **With people.** The existing `WithPeopleCard`, unchanged.
- **Removed:**
  - "Category Movers vs. Recent Average": the running-high rows replace it, and Reports has
    `CategoryChanges`;
  - "Recurring Payments & Subscriptions": its rows move to "Paid regularly" and its questions to "To look at";
  - the separate "Money with people" card: its rows join "To look at".

## Sidebar dot

The sidebar's Insights item shows a dot while "To look at" has rows. It uses the same query key as the
page (`['ai-insights', 'look-at', null]`), so there is no second request while the page is open.

## Reports month link

"See the full month in Reports" opens `/app/reports?month=<reporting month>`. Reports opens on that month
when it is one of its months, and on the newest month otherwise, as today.

## Calls made in the build (Sid can check these)

- No month picker on the website in this version.
- The undo window is 5 seconds.
- "First month" means Reports lists one month or none.
