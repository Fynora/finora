# Public repository policy

## Status

This repository is **intentionally public**, as of 2026-09-08 (`5be9543b`,
"pre-public-visibility checklist"). This is a deliberate, temporary operational decision, not an
open-source release.

## Why

Development velocity and GitHub Actions cost. A public repository gets free, effectively
unlimited GitHub-hosted runner minutes — including macOS runners, which are billed at a real
multiplier on a private repo — plus free CodeQL and free secret scanning. At this project's
current CI volume and pre-launch pace, that materially reduces cost without materially changing
risk, given the controls below.

## What public visibility does *not* mean

- **The code remains proprietary.** See `LICENSE`: "No license is granted to any person to use,
  copy, modify, merge, publish, distribute, sublicense, and/or sell copies of this software."
  Public visibility is read access for transparency, not a grant of rights.
- **No external contributions are accepted.** This is not an open-source project soliciting
  pull requests. Unsolicited PRs will be closed.
- **Issues-only for external reports.** The one legitimate external-facing channel is GitHub
  Issues for bug reports, and GitHub's Private Vulnerability Reporting for security findings —
  see `SECURITY.md`.

## Security controls active during the public period

Verified directly (not assumed) as of 2026-09-09:

| Control | Status |
|---|---|
| Secret scanning | Enabled |
| Push protection | Enabled |
| Gitleaks — per-PR diff scan | Passing (`ci.yml`) |
| Gitleaks — nightly full-history scan | Runs daily, 03:30 UTC (`gitleaks-nightly.yml`) |
| Dependabot | Enabled across maven/npm×4/github-actions/docker |
| CodeQL | Running (JavaScript/TypeScript + Java) |
| CODEOWNERS | Present (`.github/CODEOWNERS`) |
| Workflow trigger scoping | `pull_request` (not `pull_request_target`) — fork PRs do not get secret access by default |

What these controls guarantee: no committed secret should reach the public repository undetected,
and no fork PR gets access to repository secrets. What they do **not** guarantee: the full source
code — parsing logic, business rules, API surface, architecture — is visible to anyone, and no
control here changes that. Going public is a bet that source-code visibility itself is an
acceptable risk during this phase; it is not a bet that secrets are protected (those were already
protected, public or not).

## Re-privatization

**Re-privatization is a business decision by Fynora, not an automatic or purely
cost-driven one.** Tying it only to GitHub Actions usage would answer the wrong question — the
reason the repository *went* public (cost) and the reason it should *stop* being public (risk, as
the product moves toward handling real users' real financial data) are different questions, and
only the second one should drive this decision.

**The specific trigger has not yet been finalized — this is the one open item in this policy.**
Candidate triggers discussed:

- First production customer / first real financial data flowing through the system.
- Public beta complete.
- A specific launch milestone.
- A fixed calendar date, with required active re-approval to extend rather than silent
  continuation past it.

Whichever is chosen, the mechanism should defeat "temporary became permanent by nobody deciding
when it ends" — a fixed date requiring an active, deliberate renewal is the strongest form of
that, and should be combined with whichever product-milestone trigger is picked, not used alone.

**Action item**: pick and record the actual trigger here. Until this section is filled in with a
concrete date/condition, treat the public status as under-governed even though the technical
controls above are in place.
