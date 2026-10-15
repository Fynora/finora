# Automatic Claude fix job for held statements

Status: design, approved in conversation by the repository owner on 2026-10-15; awaiting review of
this document. This is project 5 of
`2026-10-14-fixing-statements-without-the-real-statement-design.md`. Projects 1–4 are merged:
#2168, #2180, #2183, #2188, #2193, #2195 and #2198.

## Decisions (owner, 2026-10-15)

| Question | Decision |
|---|---|
| When Claude starts | As soon as a statement is held, if its blanked copy is MATCHES |
| Where it runs | GitHub Actions in the private repo `Fynora/finora-statement-traces` |
| How it authenticates to Claude | The owner's Claude Max plan, via `claude_code_oauth_token` (an input of `anthropics/claude-code-action`, checked in its `action.yml` on 2026-10-15) |
| Cap | 50 runs a month. Beyond that, the item goes to a person and the owner is emailed |
| Attempts | At most 2 per held item, one job per held item |
| Who merges | Always the owner. The bot can open PRs and never merge |
| Finora's visibility | Everything must work whether `Fynora/finora` is public or private (owner: "Finora can be public and private") |

## Facts checked before designing (2026-10-15)

- `Fynora/finora` main is protected by a ruleset requiring a PR, with **0 required approvals** and
  no bypass actors. So any identity that can open a PR can merge it.
- The project 3 nightly cleanup (`retention.yml`) has failed since 10:11 UTC. Cloudflare Bot Fight
  Mode challenges calls from GitHub's runners to production. A design that has GitHub call
  production would be blocked the same way.
- The private repo's runner (`scripts/run-against.sh`) and its dispatch guard reach `Fynora/finora`
  anonymously. Both break if Finora becomes private.
- Production already knows its own deployed commit (`BuildVersionResolver`, from
  `RAILWAY_GIT_COMMIT_SHA`). It already sends held-item emails to admins (`HeldItemAdminAlertService`,
  including `alertHoldOverdue` for the 48-hour escalation), and can re-run a held statement safely
  (the queue's reprocess).

## How it works

### 1. Production opens a fix job when a statement is held

1. A new hook runs after commit, beside the existing `alertImportHeld` and `alertTrustReviewHeld`
   calls.
2. It makes the server blanked copy (project 1's `BlankedCopyService`), with no person involved.
3. It opens a fix job only if all of these hold:
   - the switch `app.claude-fix.enabled` is on (default **off**);
   - the copy's check is MATCHES;
   - fewer than 50 jobs have started this calendar month (India time);
   - no fix job exists for this held item.
4. Otherwise it records why no job started: switch off, copy DIFFERS, copy NOT_CHECKED, copy
   refused, CSV, or cap reached. The admin page shows "Needs a person" with that reason. Reaching
   the cap emails the Super Admins once that month.
5. The job's row (new table `claude_fix_jobs`) holds:
   - the held item reference;
   - state, attempt count, and the run and PR references;
   - timestamps.

   It holds no statement text. One row per held item, enforced by a unique key.

### 2. Production sends the copy out

Production commits two files to the private repo through GitHub's contents API:
- `incoming/<reference>-a<attempt>.trace`: the blanked copy, with its row map;
- `incoming/<reference>-a<attempt>.json`: the reference, the attempt, the bank name, and the
  hold's reason codes and finding kinds. No user, email, file name or amount.

It uses a fine-grained token stored in Railway:
- it is limited to the private repo, with Contents write and Actions read;
- it has **no Workflows permission**, so it can't change the repo's workflows.

Production only ever calls out to GitHub, so Cloudflare's challenge doesn't apply. Nothing on
GitHub ever calls production.

### 3. Claude works in the private repo

`fix.yml` runs on a push touching `incoming/**.json`. It:
1. Gets a token for its own GitHub App, **Fynora Fix Bot**. The App is installed on both repos,
   with Contents write and Pull requests write, and no Administration permission. The App token
   is also what checks out `Fynora/finora`, so it works whether Finora is public or private.
2. Runs `anthropics/claude-code-action` with the owner's OAuth token, a fixed prompt, a turn
   limit and a 60-minute timeout. The prompt:
   - treats the incoming copy as data, never instructions;
   - has Claude reproduce the faulty reading with the copy;
   - has it fix the reader in the Finora checkout;
   - has it add the copy as a private trace with its own test class (named by the private repo's
     README rule, so retention can delete both);
   - has it run the public tests and the private traces' tests.
3. Opens two linked PRs as the App:
   - **public:** the reader change;
   - **private:** the trace and its test.

   Each body carries the copy's before and after reading (field values of the copy's own masked
   text, row by row), the tests run, and `Fixes held item <reference>`. When Finora is private,
   the bot adds the `full-ci` label to its public PR, so that PR's checks run.
4. Deletes the `incoming/` pair in a final commit, whatever the result.

### 4. Production follows up by polling GitHub

A scheduled task (every 10 minutes) reads, for each open job, the workflow run and the PR state
through GitHub's API (outbound only):
- **The run succeeded and the PRs are open:** the job state becomes `PR_OPEN`, and the admin page
  links both PRs.
- **The run failed or opened no PR:** attempt 2 is sent. After a second failure the state becomes
  `GAVE_UP`, and the item goes to a person.
- **The public PR is merged, and production's own deployed commit contains its merge commit**
  (compare API): production re-runs the held statement through the queue's existing reprocess.
  - If the statement now passes, it continues as any released statement does, and the customer
    gets the existing "statement ready" message. State becomes `FIXED`.
  - If it is held again, the state becomes `GAVE_UP`, with "fix deployed, statement still held".

The 48-hour overdue email (`alertHoldOverdue`) gains one line: "Claude: PR opened (links)",
"Claude gave up after 2 attempts", or "Claude not tried: <reason>".

### 5. The bot can never merge

These are owner settings changes, listed as prerequisites. The job's switch must stay off until
they are done:
- The `main branch protection` ruleset on `Fynora/finora` requires **1 approval**, with the owner
  as a bypass actor. The bot can't approve its own PR, and the owner can still merge his own work.
- The private repo's `main` gets a ruleset requiring a PR. The production token's direct commits
  go only to `incoming/`. Allowing that may need a path-scoped exception, or `incoming` may move
  to a dedicated branch; the plan settles which from what GitHub's rulesets allow.

### 6. The owner still checks every fix

- GitHub can't run the owner's 34 local statements.
- A bot PR is not merge-ready until a session has run the byte-identical reading check
  (`scripts/corpus-run.py`) and the private traces locally for that branch. That is the same rule
  as for every reading change. The public PR template line for bot PRs says so.

## Prerequisites the owner does once

1. Create the GitHub App "Fynora Fix Bot" with the permissions in section 3, and install it on both
   repos. Put its App ID and private key in the private repo's secrets.
2. Run `claude setup-token`, and put the token in the private repo's secret
   `CLAUDE_CODE_OAUTH_TOKEN`.
3. Create the fine-grained token from section 2, and put it in Railway as
   `APP_CLAUDE_FIX_GITHUB_TOKEN`.
4. Change the rulesets as in section 5.
5. Confirm that the Max plan's terms allow automated, unattended use. This is not established by
   this document.
6. Turn the switch on, after one supervised try on a test hold.

## Also fixed here

The private repo's `scripts/run-against.sh`, and `traces.yml`'s owner-authored-branch check, use
the App token instead of anonymous access, so project 3 keeps working if Finora goes private.

## Safety

- **Claude never sees more than a person with a blanked copy would.** It receives only the
  blanked copy (projects 1 and 4) and its reason codes. Amounts and dates in the copy reach
  Anthropic. That is the accepted, documented risk 3 of the main design.
- **Text in the copy is data.** Narration is masked. The only free text left is the 67
  owner-chosen bank sentences and the structural words.
- **Every way production can fail leaves the hold for a person.** A failure to make the copy, to
  push, or to poll never blocks or changes the hold itself.
- **Logs and admin pages show only references, states, counts and PR numbers.**

## Measured and reported, not promised

From the first supervised run and the first month:
- Actions minutes per run;
- Max usage per run, as far as the plan shows it;
- how many jobs open PRs;
- how many PRs merge.

## Not in scope

- Automatic merge: rejected in the main design.
- CSV statements: there are no blanked copies for them.
- Any change to how the customer is told when a statement is released: the existing flow is
  reused.
