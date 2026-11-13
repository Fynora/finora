# Background jobs health

**Status:** design, awaiting Sid's approval (2026-11-13).

## Why

On 2026-11-13 we found that a nightly check had failed every night for four weeks and nobody had
noticed. Looking at the backend's own background work showed the same blind spot, and there it
affects customers directly.

The backend runs 41 scheduled methods, across 40 classes: purging accounts customers asked to delete,
sending notifications, escalating overdue held statements, checking subscriptions, granting referral
credit, retention sweeps and more. None of them records when it last ran or how that went. And 33
of the 40 classes contain catch blocks, the usual pattern being a sweep that catches one item's
failure, logs it and moves on, so a sweep that fails on 2 of 10 accounts finishes normally. A broken job is visible only in logs or Sentry, which nobody watches from a
phone.

Sid is often away from a laptop, so a page alone is not enough: he wants an email when something
breaks.

## Goals

1. A **Background jobs** page in the admin portal listing every scheduled job, with each one's state:
   - **OK**: last run finished with no failures;
   - **Partly failed**: last run finished but reported failed items ("2 of 10 failed");
   - **Crashed**: last run ended in an error;
   - **Overdue**: hasn't run (or hasn't finished) when it should have;
   - **Off**: switched off on purpose (for example Gmail sync, Account Aggregator).
2. A **"Needs attention" item on the dashboard** when any job is Partly failed, Crashed or Overdue.
3. An **email to admins** when a job breaks, one more if it is still broken after 24 hours, and one
   when it works again.

## Non-goals

- GitHub workflows in the private repo (nightly traces, retention, privacy scan, fix job). That is
  group 2, a later piece of work; its open question is which token may read run results.
- The whole backend being down. If the app is down, it can't email about itself; Railway and the
  System Health page cover that.
- Running a job from the page, durations charts, or performance tuning.

## Anti-goals (failure even if it works)

- The page says "all fine" while a job is quietly failing.
- A flood of emails: one job that runs every 30 seconds must never send an email every 30 seconds.
- Customer data in the page, the database rows or the emails. Production has real customers.
- Recording a run ever breaking, slowing or changing the job itself.
- "Off" jobs shown as broken, or broken jobs shown as "Off".

## Constraints

- Database pool is capped at 10 connections. Recording must cost one small write per run, never a
  row per run for jobs that succeed.
- Jobs may run on more than one backend instance; no scheduler lock exists today.
- A deploy restarts every job; a fresh start must not look like a missed run.
- Times shown and emailed in IST, as everywhere else in the portal and admin emails.

## Approaches

1. **Automatic recording plus a small report from each job (chosen by Sid).** One central hook
   records every run of every scheduled job (start, finish, crash) with no change to the jobs. Each
   job that catches its own errors adds one line in that catch saying an item failed, and each job
   with an on/off switch says when it skipped because it is off. Catches crashed, overdue and partly
   failed. Touches most job classes lightly.
2. **Automatic recording only.** No job changes; misses every failure a job catches itself, which is
   where most failures hide (33 of 40 classes have catch blocks). Rejected: it would make the page say "all fine" wrongly.
3. **Sentry cron monitoring.** Sentry is already in the backend and can email on missed or failed
   runs, but there is no portal page, it costs per job, and it is equally blind to caught errors.
   Rejected.

## Design

### Which jobs exist

At startup the backend lists every `@Scheduled` method that is actually scheduled, with its trigger
(fixed delay or rate and its interval, or cron expression), from Spring's own scheduled-task registry.
The job's key is `ClassName.methodName`.

A hand-written catalogue gives each key a plain name and one line on what it does ("Deletes accounts
customers asked to delete, after the waiting period"). A test fails if any scheduled method has no
catalogue entry, or the catalogue names a method that no longer exists, so a new job can't be added
without showing up on the page.

### Recording a run

Around every scheduled method: note the start, run it, then record the outcome. Outcome order:
an error escaping the method is **Crashed**; otherwise any reported failed items is **Partly failed**;
otherwise a reported "switched off" is **Off**; otherwise **OK**.

The job report is a small per-run API the job calls from inside the run: one call per failed item
(with the exception), optionally a count of items handled, and one call when it returns early because
it is switched off. Calls outside a recorded run do nothing.

Recording must never throw into the job or delay it noticeably: a failure to record is logged and
swallowed. The job's own behaviour, return value and exceptions are unchanged.

### What is stored

- **`background_job_status`**, one row per job, updated in place: last started, last finished, last
  outcome, failed-item count of the last run, last successful finish, how many runs in a row have
  failed, broken since, when the last email was sent and of which kind, and which instance ran it last.
- **`background_job_failures`**, one row per run that crashed or partly failed: job, time, outcome,
  failed-item count, and the error's **type and code location only** (for example
  `DataIntegrityViolationException at AccountPurgeSweepService:212`). Never the error message, which
  can hold an email, a name or an id. Kept 30 days: the 5-minute checker below deletes older rows.

Successful runs write nothing new: they only update the job's one status row.

### Overdue

- Fixed delay or rate: overdue when no run has started within twice the interval plus 5 minutes after
  the last one finished.
- Cron: overdue when the last start is more than 30 minutes older than the most recent time the cron
  says it should have fired.
- A run that started and never finished is overdue on the same clock, shown as "Running since …".
- After a restart, each job gets its initial delay plus one interval before it can be overdue.
- With more than one instance, the latest run across instances counts.

### Emails

A checker runs every 5 minutes. It recomputes overdue and decides emails per job:

- **Broken**: the first time a job becomes Partly failed, Crashed or Overdue. Subject
  "Background job needs attention: <name>". Body: what the job does, what happened ("crashed",
  "2 items failed", or "hasn't run since 14:05 IST"), since when, and a link to the job on the page.
- **Still broken**: once, 24 hours after the first email, if it hasn't recovered.
- **Working again**: on the first clean run after a Broken email.

No other emails. Recipients are admins holding `PLATFORM_DIAGNOSTICS_VIEW`, the permission System
Health uses, through the same recipient lookup the held-statement alerts use.

The checker is itself a recorded job, and the page works out overdue live when it is opened, so a
stuck checker still shows as Overdue on the page.

### The page

New page **Background jobs** in the System group of the sidebar, permission
`PLATFORM_DIAGNOSTICS_VIEW`, built to work at phone width like the held-statement screens.

```
Background jobs                                   2 need attention
┌──────────────────────────────────────────────────────────────┐
│ ● Crashed   Account deletion sweep      last run 14:05 IST   │
│             Deletes accounts customers asked to delete       │
│ ● Overdue   Notification sender         last run 3 h ago     │
├──────────────────────────────────────────────────────────────┤
│ ○ Off       Gmail discovery             switched off         │
│ ✓ OK        Referral credit             last run 12 min ago  │
│ …                                                            │
└──────────────────────────────────────────────────────────────┘
```

Order: broken first, then Off, then OK. Each row shows its schedule in words ("every 30 seconds",
"daily at 03:45"), last run, last success, and runs in a row failed. Tapping a row shows its last 30
days of failures: time, outcome, failed-item count, error type and location.

### Dashboard

The existing **Needs attention** section gets "N background jobs need attention", linking to the
page, only when N > 0.

### Rollout

Ship with emails behind `APP_BACKGROUND_JOB_ALERTS_ENABLED` (default off). Watch the page in
production for a day for false alarms, then switch emails on. *(My call: a first deploy that emails
about a dozen wrongly-marked jobs would teach Sid to ignore these emails.)*

## Left to the builder

- How the hook wraps scheduled methods (an aspect, or a decorated task scheduler), as long as a test
  proves every scheduled method is recorded.
- The catalogue's wording, and the job-report call names.
- Migration version (latest on main today is V316; check again before adding).
- Which job classes need report lines: every catch that swallows an item's failure, and every
  early return on a switched-off flag.
