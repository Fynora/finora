# The automatic Claude fix job

**What this covers:** what happens when a statement is held and Claude is asked to fix the reader,
the one-time setup the owner does before the switch is turned on, how to tell what a job did, and
what to do when something goes wrong. Design:
[`2026-10-15-claude-fix-job-design.md`](../superpowers/specs/2026-10-15-claude-fix-job-design.md).

## What it does

1. **A PDF statement is held** (either queue). After the hold commits, `FixJobStarter` makes the
   server blanked copy, with no person involved. It starts a job only when the switch
   `app.claude-fix.enabled` is on, the copy reads the same as the statement (MATCHES), this month's
   cap is not reached, and no job exists for that import job yet. Otherwise it records why not.
2. **Production sends the copy out.** Two commits to the private repository's `incoming` branch,
   through GitHub's contents API: `incoming/<job id>-a<n>.trace`, then `incoming/<job id>-a<n>.json`
   (the reference, the attempt, the bank, the hold's reason codes and finding kinds; no user, email,
   file name or amount). Production only ever calls out to GitHub.
3. **Claude works in the private repository.** `fix.yml` runs Claude Code on the owner's Max plan,
   with no GitHub credential in its environment. If its change passes every test, the "Fynora Fix
   Bot" App opens two linked PRs: in the private repository, the copy as a trace with its test
   class and the before and after reading; in Fynora/finora, the reader change, with counts and
   field names only. A guard refuses the public PR if its diff or body holds any amount or date
   from the copy, or any trace file. The incoming pair is deleted at the end, whatever happened.
4. **Production follows up every 10 minutes** (`FixJobFollowUp`): PRs opened, or a second and last
   attempt, or give up. Once the public PR is merged **and** production's own deployed commit
   contains its merge commit, it re-reads the statement through the queue's own re-run.
5. **A person merges, always.** The bot cannot approve or merge.

A bot PR is not merge-ready until a session has run `scripts/corpus-run.py` (byte-identical) and
the private traces locally for its branch, as for any reading change
([`private-statement-traces.md`](private-statement-traces.md)).

## Setup, once, by the owner

Do these in order. The switch stays off until every step before it is done.

1. **Create the GitHub App "Fynora Fix Bot".** GitHub, Fynora organisation, Settings, Developer
   settings, GitHub Apps, New GitHub App.
   - Webhook: off. Homepage URL: the Finora repository.
   - Repository permissions: Contents **read and write**, Pull requests **read and write**,
     Metadata read (forced), Actions read. Nothing else: no Administration, no Workflows.
   - Install it on the Fynora organisation, **only** on `finora` and `finora-statement-traces`.
   - In `finora-statement-traces`, Settings, Secrets and variables, Actions, add the secrets
     `FIX_BOT_APP_ID` (the App's ID) and `FIX_BOT_PRIVATE_KEY` (a private key generated on the
     App's page, the whole `.pem` file).
   - From then on the private repository's `traces.yml` and `privacy-scan.yml` reach Fynora/finora
     with this App's token, so they keep working if Finora goes private. Until then they stay
     anonymous.
2. **Run `claude setup-token` on your Mac**, and add its output as the private repository's secret
   `CLAUDE_CODE_OAUTH_TOKEN`.
3. **Create a fine-grained personal access token for production.** GitHub, Settings, Developer
   settings, Fine-grained tokens, Generate new token.
   - Resource owner: Fynora. Repository access: only `finora-statement-traces`.
   - Permissions: Contents **read and write**, Actions **read**, Pull requests **read**. No
     Workflows.
   - Expiry: 90 days. Add a calendar reminder to rotate it.
   - Put it in Railway, production backend, as `APP_CLAUDE_FIX_GITHUB_TOKEN`.
4. **Create a second, read-only token for Finora itself.** Same place. Repository access: only
   `finora`. Permissions: Contents **read**, Pull requests **read**. Nothing else. Expiry 90 days.
   Put it in Railway as `APP_CLAUDE_FIX_PUBLIC_READ_TOKEN`. While Finora is public the follow-up
   works without it; once Finora is private, it cannot see the fix PR or the deployed commit
   without it.
5. **Create the `incoming` branch** in `finora-statement-traces`, from `main`. Leave it
   unprotected: production writes there and nowhere else.
6. **Rulesets.**
   - Finora's `main branch protection` ruleset: required approvals **1**, and add yourself to the
     bypass list (role Admin, "pull requests only"). The bot can never approve its own PR.
   - `finora-statement-traces`: a new ruleset on `main` requiring a pull request, 0 approvals, no
     bypass for the App.
   - `finora-statement-traces`, Settings, General: turn on "Automatically delete head branches".
7. **Confirm the Max plan's terms allow unattended, automated use.** Nothing here establishes it.
8. **One supervised run.** Either a synthetic test hold in production with the switch on for that
   one hold, or the dry run: push a synthetic copy pair to `incoming` yourself (see "Dry run"
   below). Check the two PRs, then close them and delete their branches.
9. **Turn the switch on:** Railway `APP_CLAUDE_FIX_ENABLED=true`. The monthly cap is
   `APP_CLAUDE_FIX_MONTHLY_CAP` (default 50).

### Dry run

Before the code merges, set the private repository's Actions variable `FIX_FINORA_REF` to this
change's Finora branch (the workflow then starts from it instead of `main`), and remove it after.
Push a synthetic pair to `incoming`: a blanked copy of a synthetic statement (never a customer's)
as `incoming/<uuid>-a1.trace`, then `incoming/<uuid>-a1.json` with
`{"reference": "<uuid>", "attempt": 1, "bank": null, "reasonCodes": [...], "findingKinds": []}`.

## Reading a job's state

The held-imports panel and the held-statement page show a "Claude fix" line, and the 48-hour
overdue email carries a "Claude:" line.

| State / reason | Admin page | Overdue email |
|---|---|---|
| switch off | Claude fix off | Claude: off |
| not started: OFF (held while the switch was off; shown once it is on) | Needs a person: the Claude fix was off when this was held | Claude: not tried (it was off when this was held) |
| not started: DIFFERS, NOT_CHECKED, REFUSED, CSV | Needs a person: the blanked copy does not match / was not checked / was refused, or the statement is a CSV | Claude: not tried (the same reason) |
| not started: CAP_REACHED | Needs a person: this month's 50 Claude runs are used | Claude: not tried (monthly limit reached) |
| not started: ERROR | Needs a person: the Claude fix could not start | Claude: could not start |
| SENT | Claude is working on it (attempt n of 2) | Claude: working (attempt n of 2) |
| PR_OPEN | Claude opened a fix: PR #p (Finora), #q (private) | Claude: PR #p opened |
| REREADING | Fix deployed, re-reading the statement | Claude: fix deployed, re-reading |
| FIXED | Fixed by Claude's PR #p | Claude: fixed by PR #p |
| GAVE_UP: RUN_FAILED, NO_PR | Needs a person: Claude gave up after n attempts | Claude: gave up after n attempts |
| GAVE_UP: STILL_HELD | Needs a person: Claude's fix was deployed but the statement is still held | Claude: fix deployed, statement still held |
| GAVE_UP: DECIDED | Claude stopped: a person decided this hold | Claude: stopped, a person decided this hold |

The rows live in `claude_fix_jobs` (V274): one per held import job, with the state, the reason,
the attempt, the commit that sent it and the two PR numbers. Never statement text. Logs name the
job id, the state and exception class names only.

A trust-queue statement whose re-read clears is FIXED and waits, ready for import, for a person to
approve it, as any re-run that clears does. An import-queue statement is requeued, and FIXED once
it completes.

## When something goes wrong

- **Every failure leaves the hold for a person.** Making the copy, sending it, or polling GitHub
  can fail; none of them changes the hold.
- **A send that fails** records `ERROR`; nothing retries it. The item is in the queue as usual.
- **A run that fails or opens no PR** is sent again once (attempt 2). A run that has not started
  90 minutes after its send counts as failed.
- **The guard refused the public PR:** the private PR is open, and the run is red. Read the
  private PR, make the public change by hand if it is right, or close it.
- **A job stuck in PR_OPEN** after its PR merged: the follow-up waits until production's deployed
  commit contains the merge. A requeue refused because the same file is live under another import
  is tried again every 10 minutes.
- **Turning the switch off** stops everything at once: no new jobs, no sends, no polling. Open jobs
  pick up where they were when it is turned on again. Statements held while it was off are
  recorded as such and stay with a person: turning it on does not send them.
- **A hold whose start never ran** (the fix job's queue was full, or the server restarted first) is
  started by the follow-up's catch-up within about 10 minutes, if it was held in the last 24 hours.
- **A run that never finishes** (no runner, no Actions minutes left) counts as failed after 3 hours.

## Cost

Each attempt is one `fix.yml` run in the private repository, billed against the 2,000-minute
monthly allowance, at most 90 minutes (Claude's 60, then the full unit and private-trace suite).
Not yet measured. Each private PR the bot opens also runs `traces.yml` and `privacy-scan.yml`
(about 9 minutes). Claude's own usage counts against the owner's Max plan.

**The cap does not keep this inside the allowance.** 50 jobs a month, at most 2 attempts each, is
up to 100 runs: at the 90-minute ceiling that is 9,000 minutes, and even at 20 minutes a run it is
2,000, the whole allowance, which `traces.yml` and `retention.yml` also need. If the allowance runs
out, those stop too, and retention is how a deleted customer's trace leaves the private repository.
Measure the first supervised run's minutes, then set `APP_CLAUDE_FIX_MONTHLY_CAP` so that
cap x 2 x minutes-per-run leaves room for the nightly workflows (about 180 minutes a month).

## Known limits

- The private PR's branch and GitHub's record of that PR keep the trace after retention deletes it
  from `main`. "Automatically delete head branches" removes the branch on merge or close; GitHub
  keeps a closed PR's commits for good.
- A copy pushed to `incoming` in the moment the workflow resets that branch is lost; production
  then counts that attempt as failed.
