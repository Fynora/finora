# Admin push campaigns: runbook

What it is: an admin writes any push notification, picks an audience, tests it, then sends it now or
schedules it (once, or daily at a time in IST) and decides when it stops. Backend: `com.finora.notification.campaign`
(PR 1). Admin screen: admin portal > System > Push Campaigns (`/push-campaigns`, needs `PUSH_CAMPAIGN_MANAGE`).
It only drives the API under `/api/v1/admin/push-campaigns`; every rule below lives on the server. The screen
asks for the word SEND when a send now reaches more than 1,000 people (or when the audience cannot be
counted), and shows the same live delivery counts the API returns.

## How it sends

Campaign run -> batched enqueue into the notification outbox (`type = CUSTOM_PUSH`, PUSH only,
category FINANCIAL, priority LOW) -> the existing `NotificationDispatcher` -> FCM. There is no second
FCM sender. The words come from the `CUSTOM_PUSH` template (`{{title}}` / `{{message}}`, V261).

The dispatcher claims by priority first (CRITICAL, HIGH, NORMAL, LOW), then due time, so a large
campaign never delays an "import ready" push or a security alert.

## Rules the code enforces

| Rule | Where |
|---|---|
| At most N custom pushes per person per IST calendar day, across all campaigns. N is set by an admin on the Push Campaigns screen (1 to 10, default 1). One campaign never takes two slots from the same person in a day | `custom_push_daily_cap` (a slot per row, V262), claimed with `INSERT ... ON CONFLICT DO NOTHING` one slot at a time before a person is queued; limit in `push_campaign_settings` (read fresh for every page) |
| Scheduled sends only 07:00-21:59 IST (send now ignores this) | `ScheduleCalculator` |
| A due slot more than 2 hours late, or due outside the window, is recorded MISSED and not sent | `ScheduleCalculator.decide` |
| Send now counts as today's run: a daily campaign due today moves to tomorrow; a one-off completes | `PushCampaignService.sendNow` |
| Audience: ACTIVE end-user account, at least one live device token, FINANCIAL push not switched off | `AudienceSql.BASE` |
| Test sends use their own key prefix, no cap row, no run row, NORMAL priority | `PushCampaignService.sendTest` |
| Edits only in DRAFT or PAUSED; STOPPED and COMPLETED are final (clone to send again). An edit may carry the `version` the editor loaded; a stale one is refused (409) so two admins cannot overwrite each other | `PushCampaign`, `PushCampaignService.update` |
| Emergency brake: `POST /{id}/cancel-sending` (any status) cancels a run still queuing people and withdraws every push of the campaign that is queued but not yet handed to the dispatcher, and gives those people their one-per-day slot back, so a corrected message sent the same day still reaches them. **Stop does this too.** Pushes already claimed by the dispatcher (at most one batch of 50) still go | `CampaignCancellation`, `PushCampaignService.cancelSending` |
| Own permission `PUSH_CAMPAIGN_MANAGE` (ADMIN, SUPER_ADMIN); every action audited with the acting admin | V261, `PushCampaignService` |

Content: because the push goes out as FINANCIAL (the one category users can switch off), messages should
be operational or product ("upload your statement", "your report is ready"), not promotions. Code cannot
enforce this.

## Switches and settings

| Setting | Default | Effect |
|---|---|---|
| Feature flag `PUSH_CAMPAIGNS_ENABLED` (Admin portal > Feature flags) | on | Master off switch. Off: nothing is claimed by the scheduler, send now and send test are refused. A slot that came due waits and, when switched back on, the missed-run rule decides. |
| `app.push-campaigns.max-audience` (env `APP_PUSH_CAMPAIGNS_MAX_AUDIENCE`) | `100` | Staged rollout limit. Start, resume and send now are refused when the audience is larger; a scheduled run whose audience grew past it is recorded FAILED and sends nothing. Raise it in steps (100, then 1,000, then a large number). |
| `app.push-campaigns.drain-max-seconds` | `30` | After queuing, a run keeps draining the notification queue this long; the dispatcher's poller delivers the rest. `0` turns the drain off. |
| `app.push-campaigns.page-size` | `200` | People queued per short transaction. |
| `app.push-campaigns.scheduler.enabled` | `true` | Turns the once-a-minute scheduler off (off under test). |

## Before the first real campaign

1. Production has real Firebase credentials (`GOOGLE_APPLICATION_CREDENTIALS_BASE64`); without them the
   no-op push provider is used and nothing is delivered. Production refuses to boot without them.
2. Send a **test** to an internal account (by user id, or by email, which always means the end-user account
   and never an admin account that shares the email) and confirm it arrives on a real iPhone and a real Android.
3. Real FCM speed has not been measured. The dispatcher sends 50 rows per pass, one at a time; unaided
   that is about 100 people a minute. Measure on dev before raising `max-audience` past 1,000.
4. Legal consent for non-transactional push has not been reviewed.

## Stopping everything

**A wrong message already sending:** call `POST /api/v1/admin/push-campaigns/{id}/cancel-sending` (or Stop,
which also ends future runs). It withdraws everything still queued and reports how many; those rows become
`CANCELLED` (not failures) and their people can be sent the corrected message the same day.

**Everything at once:** switch `PUSH_CAMPAIGNS_ENABLED` off. That stops new runs and send now / send test, but
pushes already queued still deliver; cancel each campaign's sending as above to withdraw those.

## Known limits

- With strict priority ordering a LOW row can wait while higher-priority rows keep arriving. Not realistic
  at current volume; revisit with "age eventually wins" only if queue metrics show it.
- A run that dies half way stays RUNNING until it has made no progress for 30 minutes (every page it queues is a heartbeat, so a slow but live run is never cut off), then the scheduler marks it FAILED. Pressing
  send now again finishes it for a daily/draft campaign (people already queued are skipped by key and cap).
  A finished one-off cannot be re-sent; clone it (same-day clones skip everyone already reached).
- Per-run sent / failed / pending counts are read live from the outbox by key prefix, so they keep moving
  while the dispatcher works.
- `custom_push_daily_cap` rows older than 30 days are deleted daily by the scheduler.
- **The daily limit** (`GET`/`PUT /api/v1/admin/push-campaigns/settings`, screen: Push Campaigns > "Most campaign
  pushes one person gets in a day") applies from the next page any run queues. Lowering it never takes back a
  push someone already has; raising it opens the extra slots at once. The screen asks for a confirmation only
  when raising. Every change is audited (`PUSH_CAMPAIGN_SETTINGS_CHANGED`, old and new value). More pushes a day
  means more people may switch off the FINANCIAL category, which also stops their bill and due-date warnings:
  watch opt-outs after raising it. The 1 to 10 bounds are in the API and a table CHECK; raising the top is a
  migration.
- Claiming is one `INSERT ... ON CONFLICT DO NOTHING` per slot tried, so a person who has reached the limit
  costs up to `limit` failed inserts. Measured on local Postgres (autocommit, one round trip each): about
  0.3 ms for a claim that wins, 0.25 ms for a loss at limit 1, 0.8 ms at limit 3 and 2.8 ms at limit 10. Ten
  thousand people at limit 10 is about 28 s of claiming against about 100 minutes of delivery at the
  dispatcher's pace, so it is not the bottleneck. Redis was considered for the counters and rejected: the app
  treats Redis as optional (it boots and fails open without it), so a limit kept there could reset or stop
  holding silently; a claim has to commit or roll back together with the outbox row, and a cancel has to hand the
  slot back in the same statement, which only one Postgres transaction gives.
