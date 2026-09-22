# Incident: customer PII committed to Git history

**Date identified:** 2026-08-03
**Status:** Contained in current code. **Closed 2026-09-22 — Option A, accepted. See §6.**
**Severity:** High. The repository is confirmed public (§3 item 1) with clone traffic far beyond
the core team (§3 item 5), which is the doc's own trigger for High rather than the provisional
Medium above.

This is recorded as a security incident rather than as an engineering to-do. Customer personal data
reaching a version-controlled repository is a data-handling failure with a decision trail worth
keeping, regardless of how small the blast radius turns out to be. The point of this document is
that the decision — including a decision to accept the risk — is written down with its reasoning.

---

## 1. What happened

A real customer's name and their 14-digit bank account number were committed inside a Java source
comment in `PdfTableLocator.java`. The comment documented what a real Bank of Baroda statement's
repeated page banner looked like, quoting the banner verbatim:

```
// printed at the top of EVERY page ("<HOLDER NAME> SAVINGS ACCOUNT  - <14 digits>"),
```

(shown here in its redacted form)

The data was **not** in a fixture, a test resource, or a database — it was in main source, which is
why it went unnoticed.

## 2. Timeline

| When | What |
|---|---|
| commit `6a188da` | Introduced, while documenting a real-document bug fix. |
| — | `scripts/check-fixture-hygiene.sh` did not scan it: its target filter was `test/`, `fixtures/`, `resources/`, `*Test.java` and `*.md`. A main source file matched none of those. |
| commit `2231b2f` (attempt) | The same number was copied *out* of that comment into a new synthetic fixture. The hook fired on the copy, blocking the commit. |
| commit `74a3d76` | Comment redacted to describe the shape (`<HOLDER NAME>`, `<14 digits>`) rather than the values. Hygiene scan widened from location-based to extension-based, so every `.java`/`.ts`/`.sql`/`.md` file is now scanned. Verified to fire by staging a main-source file containing an email and an IFSC. |
| 2026-08-03 | This record created. |

## 3. Exposure assessment — **complete before deciding**

None of these should be assumed. Record the answer next to each, including "none found" — an
unanswered box and a box answered "no" are not the same evidence.

| # | Question | Answer | How to check |
|---|---|---|---|
| 1 | Repository visibility — private or public? | **Public** (checked 2026-09-22 via `gh repo view --json visibility`). | `gh repo view --json visibility`, or the GitHub UI |
| 2 | Number of collaborators with access | **1** (`siddharth705`, via `gh api repos/Fynora/finora/collaborators`) — narrow, but irrelevant to a public repo's own visibility. | Settings → Collaborators and teams |
| 3 | **Has the repository ever been public?** | **Not established.** No audit-log access from this session; unknown whether it was already public on 2026-08-03 or went public afterward. | Repo audit log; a repo made private later still leaves earlier commits reachable |
| 4 | Do any forks exist? | **0** (checked 2026-09-22 via `gh repo view --json forkCount`). | Insights → Forks. A fork retains the original history even after an upstream rewrite |
| 5 | Has anyone outside the core team cloned or downloaded it? | **Yes, at volume.** `gh api repos/Fynora/finora/traffic/clones` (2026-09-22): 24,604 clones / 1,360 unique cloners in the trailing 14 days alone. GitHub's traffic API can't distinguish real developers from bots, mirrors, or scanners, so this overstates how many people actually read the exposed line — but it rules out "nobody outside the core team has touched this." | Insights → Traffic → Clones (14-day window only — absence here is weak evidence) |
| 6 | **Could CI/CD logs, build artifacts, or backups contain the exposed data?** | **Not established.** Not checked this pass. | CI job logs, cached workspaces, artifact retention, any repo backup or mirror |

Item 6 matters as much as the repository itself and is the one most often missed: a history rewrite
removes the data from Git and leaves it untouched in a CI log, a cached build workspace, or a
nightly backup. A rewrite that stops there is incomplete, and believing it was complete is worse
than knowing it was not.

**If the repository is or ever was public**, treat Option B as the default and separately consider
whether the affected customer requires notification under the DPDP Act 2023 — that is a question
for whoever owns that obligation, not one to settle in this document.

Note for context: an account number is **not a credential**. It cannot be rotated. Removal and
access control are the only mitigations available, which is why the exposure assessment carries more
weight here than it would for a leaked key.

## 4. Decision

Choose **one**. Record it in §6 either way.

### Option A — Accept the risk

Leave Git history unchanged. Requires all four:

- [x] Rationale recorded in §6, referencing the §3 findings that support it
- [x] Current codebase confirmed clean (done — `74a3d76`)
- [x] Automated PII scanning in place to prevent recurrence (done — extension-based hygiene scan,
      plus trace-capture validation and `TraceCorpusHealthTest`)
- [ ] Exposure assessment complete, with no finding that contradicts acceptance — **not fully
      complete**: §3 items 3 and 6 are unanswered, and item 5 (public repo, high clone volume) is
      itself a finding the doc's own severity rule treats as an escalation trigger, not a clean
      pass. Accepted anyway; see §6 for why.

Defensible when the repository is private, access is limited to the core team, and no public
exposure, fork or external clone was found. Not defensible on the basis that a rewrite is
inconvenient.

### Option B — Rewrite history

Remove the exposed values from the entire history. Requires all five, **in order**:

- [ ] §5 pre-rewrite confirmations obtained from every developer
- [ ] Remove the values across all commits with a history-rewrite tool (`git filter-repo`)
- [ ] Force-push the rewritten history
- [ ] Recreate affected branches and worktrees (currently `claude/competent-bell-f17420`,
      `claude/silly-colden-4cf80e`)
- [ ] Purge any CI logs, artifacts, or backups identified in §3 item 6 — the rewrite does not
      touch them

## 5. Pre-rewrite confirmations — **required before Option B is executed**

A history rewrite is destructive to every existing clone. Do not begin until each is confirmed:

- [ ] **No developer has unpushed commits.** Anything not pushed is orphaned by the rewrite and
      cannot be recovered from the remote.
- [ ] **Every developer understands their clone must be re-synced or re-cloned**, and knows which
      they are doing.
- [ ] **Active feature branches and worktrees are backed up**, since their commit hashes all change.
- [ ] **A window is agreed** in which nobody is pushing.

Only once all four are confirmed should the rewrite be executed.

## 6. Decision record

> **DEFERRED — 2026-08-03, by Siddharth.** Consciously postponed, not overlooked. The exposure
> assessment (§3) and the decision below are to be revisited; until then this incident stays open.
>
> Recorded because a deferral and an oversight leave identical blank fields, and the difference
> matters when this is read later. Nothing here is blocking: the code is sanitised (`74a3d76`), the
> preventive measures in §7 are live, and the residual exposure is historical only.

**Decision:** Option A — Accept the risk, no history rewrite.
**Decided by:** Siddharth Tiwari
**Date:** 2026-09-22
**Rationale:** Revisiting the 2026-08-03 deferral with a completed (though not fully clean) §3.
The repository is confirmed public with clone traffic far outside the core team (§3 items 1 and 5)
— by this doc's own rule, that is the trigger for Option B as the default, not a pass. Accepted
anyway: the exposed value is a single customer's name and account number in one old commit
(`6a188da`), not a credential that grants access to anything; the current codebase has been
sanitised since `74a3d76`; and a history rewrite is destructive to every existing clone and every
other active branch/worktree on this repository today, which is an operational cost judged to
outweigh the residual risk of one already-widely-cloned historical value. §3 items 3 (ever been
public before now) and 6 (CI logs/artifacts/backups) were not run down before this decision — their
absence is a known gap in this acceptance, not evidence they'd come back clean, and either could
change this call if checked later. Preventive measures in §7 remain the control against recurrence,
not this decision.

## 7. Preventive measures already taken

- Hygiene scan is **extension-based, not location-based** (`74a3d76`) — main source, migrations and
  docs are now scanned, not just tests and fixtures. Verified to actually block.
- Trace capture now **validates before writing** and refuses on unmasked PII, rather than relying on
  the capturer reading the file (see [`trace-lifecycle.md`](../../engineering/import/trace-lifecycle.md)).
- `TraceCorpusHealthTest` fails the build on unmasked PII in any committed trace.

## 8. Remaining items to close this incident

**Engineering — one task**

- [ ] Re-capture the three `.trace` fixtures from the original PDFs, now that the redactor allowlist
      includes deposit vocabulary. **Use `./scripts/trace-capture.sh`** — see the note below on why
      this is not "capture, then review carefully".
- [ ] Replace the synthetic composite fixture with the regenerated real traces, and point
      `CompositeMultiProductClassificationTest`'s trace half back at asserting
      `FIXED_DEPOSIT`/`RECURRING_DEPOSIT` rather than "not accounts".
- [ ] Delete `buildCompositeMultiProductStatementSample` once the real traces cover it.

> **On reviewing regenerated traces.** The natural instruction here is "carefully review each trace
> before committing to ensure no PII is exposed". That was the control in force when this incident
> happened, and it is the reason it happened — a trace is thousands of coordinate lines, nobody
> reads one, and asking people to is how a customer's account number reaches a repository while
> everyone believes it was reviewed.
>
> That control is now automated. `./scripts/trace-capture.sh` **refuses to write** a trace
> containing an unmasked email, phone number or IFSC branch code, and equally refuses one that lost
> the structural evidence it was captured for. It prints a summary with a verdict. Human review is
> approving that verdict — a decision that takes seconds — not scanning the file.
>
> Please point the team at the script rather than at manual review. Re-establishing manual review as
> the primary control would undo the main preventive measure taken here.

**Governance — must be complete before the incident is closed**

- [ ] §3 exposure assessment answered in full, including CI/CD logs, artifacts and backups —
      **items 3 and 6 remain unanswered** (see §3); closed anyway, as a documented gap in the
      Option A acceptance rather than a blocker, per §6.
- [x] §4 option chosen — Option A, 2026-09-22.
- [x] §5 pre-rewrite confirmations obtained (Option B only) — **N/A**, Option A was chosen.
- [x] §6 decision and rationale recorded — 2026-09-22.

**Standing constraint.** No history rewrite or force-push to `main` may be performed without
Siddharth's explicit approval, given after confirming every developer has pushed their work. This is
a repository-level decision, not an engineering one — a rewrite orphans unpushed commits
irrecoverably, because the remote no longer contains what they were based on.

## 9. What this says about the control that failed

The hygiene hook was scoped by *directory*, which encodes an assumption that customer data only
arrives through fixtures. It doesn't. It arrives wherever someone is documenting what a real
document looked like — which is disproportionately main source, because that is where the
explanatory comments live.

The wider lesson, and the reason the redaction workflow was rebuilt alongside this: **"the author
will check before committing" is not a control.** It was the stated control in `PdfTraceRedactor`'s
own doc comment ("the person capturing the trace is the last reviewer standing between a customer's
statement and the repository") and it failed here in both directions — PII got in, and separately,
evidence got silently stripped out of three traces without anyone noticing. Both are now automated
checks.
