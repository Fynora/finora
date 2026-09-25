# Security control audit: what exists, and how completely

**Purpose.** Nine controls were spotted incidentally during the PII sanitization sweeps. That list
was observation, not an audit, and it was at risk of being read as coverage. This states each one's
status with the evidence, and says *Not verified* where the evidence is a count rather than a proof.

**The rule this applies.** "The class exists" is not evidence that "the control is complete."
`PhoneMasking` existing says nothing about whether every log site uses it.

| Control | Status | Evidence |
|---|---|---|
| Log masking (`PhoneMasking`, `EmailMasking`) | **Partial** | Both exist with tests. Referenced in 8 main-source files, but only **3 are log statements** (`TwoFactorSmsProvider` ×2, `NoOpSmsProvider` ×1); the rest use them for DTO display. Nothing prevents a new unmasked log. |
| Sentry scrubbing | **Implemented, completeness not verified** | `observability/SentryScrubber.java`, 14 scrubbed keys. A fixed key list cannot cover a field it does not know; no test asserts an unknown-field default. |
| Rate limiting | **Implemented** (re-read 2026-09-24) | `config/RateLimitFilter.java` now lists every limited route as a literal `PathPattern` (34 routes: login, refresh, register, google/apple, identify, forgot/reset password, import staging, password/phone/email change, data export, deactivate/delete, MFA verify, OTP request/login, device tokens, AA link, Fyn screenshot). Counters are in Redis. `RateLimitFilterIT.everyEndpointWithARealPerCallCostIsLimited` fails if a listed route stops tripping. Gap closed the same day: the per-IP limits bound one client only, so a shared `auth-global` ceiling was added for the five bcrypt-cost routes, and every limiter now counts in process with the same window and limit when Redis is unreachable, where it used to fail fully open. |
| Upload validation | **Partial, and self-documented** | `StatementUpload`'s own javadoc states: *"no emptiness check, no content-type check, no extension check, no magic-byte sniff."* What does exist: `max-file-size` 10 MB, `MAX_FILE_NAME_LENGTH` 120, `importStageLimiter`, and storage never trusting the client filename. No page-count cap, no decompression-bomb guard, no parser timeout verified. |
| Encrypted / malformed PDF handling | **Implemented** | `PasswordProtectedPdfTest`, 8 tests, green in the 1,745-test baseline. |
| Malware scanning | **Implemented in code, provider-gated in deployment** (2026-09-25, audit F-18) | `uploads/UploadScanGate` runs every upload through a `MalwareScanner` before any parser; `ClamAvScanner` speaks clamd's INSTREAM protocol. Enforced structurally: FG-034 (`UploadScanGuardTest`) fails the build for any `@RestController` method taking a `MultipartFile` without the gate call, falsified by hand. An infected file is a 400 plus an `UPLOAD_MALWARE_REJECTED` audit row; an unreachable scanner is a 503 under the default `reject` policy. **The gap that remains is operational:** `MALWARE_SCAN_PROVIDER` defaults to `none`, under which uploads pass unscanned and the prod profile warns at boot. Until Railway runs a clamd service and the variable is set, production is still unscanned -- see the deployment guide, "Malware scanning". |
| Tenant isolation tests | **Partial, not verified** | 19 test files reference other-user identifiers (`otherUser`, `user2`, `userB`). A file count is not proof the assertions are *denials* — the horizontal-privilege matrix (A→B account, transaction, PDF, report, export) is not shown to be covered. |
| Startup config validation | **Implemented** | `ProductionConfigValidator`, 10 assertion sites, and it now runs before the web server binds (`SmartInitializingSingleton`, guarded by rule FG-031 in `StartupConfigValidationLifecycleTest`). |
| Web security headers | **Implemented** (re-read 2026-09-24; was "Missing at the edge") | `frontend/public/_headers` and `admin-portal/public/_headers` both set a full CSP (`default-src 'self'`, explicit script/style/font/img/connect/frame allowlists, `frame-ancestors 'none'`, `object-src 'none'`, `form-action 'self'`), HSTS with `includeSubDomains`, and `Referrer-Policy`. The API sets CSP, HSTS, `X-Frame-Options: DENY`, `Referrer-Policy` and `Permissions-Policy` in `SecurityConfig.filterChain`. Still worth a `curl -sI` against a real Pages preview after any `_headers` edit — a second `/*` block silently drops the first, which has happened once. |

## The three findings worth acting on first

1. **Malware scanning is built but not deployed** (2026-09-25 update; it was absent when this list was written). Uploads are the largest untrusted-input surface. The remaining step is operational: a clamd service on Railway and `MALWARE_SCAN_PROVIDER=clamav`.
2. **Security headers are absent from `_headers`** — CSP and HSTS on a financial web app are cheap and currently missing at the CDN edge.
3. **Masking has no enforcement.** Three log sites are masked because they were fixed by hand. A fourth added tomorrow would not be. The utilities are the easy half; a guard asserting no log statement interpolates a raw email, phone or account number is the half that makes it a control.

## What this audit does not claim

It does not say the repository is insecure, and it does not say the *Implemented* rows are sufficient.
Two rows are marked *Not verified* precisely because a count was the only evidence available, and
promoting either without reading the configuration or the assertions would repeat the mistake this
document exists to correct.

---

## Accepted as the current baseline (2026-08-08)

Accepted by the repository owner. **The classifications above do not change except on new
evidence** — not on a plausible argument that a control is probably fine.

The four-state vocabulary is the load-bearing part, and it is the rule this document exists to
enforce:

| State | What it requires |
|---|---|
| **Implemented** | evidence **and** a test |
| **Partial** | the exact gap, named |
| **Not verified** | what evidence would settle it |
| **Missing** | implementation required |

> A control is not Implemented because a utility, a class, or a test file exists.

**And a value is not synthetic because a scanner accepted it.** The same error one level down, and
it was made during this work. Two real IFSCs in a `BankRegistry` javadoc were classified as confirmed
placeholders, on the reasoning that their conspicuous runs of zeros would satisfy `is_placeholder()`.
They did not: the IFSC rule requires six *identical* characters at positions 6–11, and those branch
parts each carried a differing final digit. Both had passed CI for months only because they sat on a
pre-existing line no diff had touched — so the green history was evidence about the diff-based
scanners' reach, not about the values. The pre-commit hook caught them the moment that line was
edited; both were sanitized in `b3fc79c`.

The values are not reproduced here, and that is not squeamishness: an earlier draft of this very
paragraph quoted both, and the hook blocked the commit. Explaining a leak does not license repeating
it.

Two things follow. A predicate's acceptance is evidence about the predicate, not about the value's
origin — only the corpus comparison establishes origin. And the classification of a value must cite
the source evidence, never the scanner's verdict on it.

## Prioritised work, in order, not in parallel

**Before any of it:** finish the repository PII sanitization against the 1,745-test / 134-affected
baseline. No parser behaviour changes as part of that cleanup.

**And nothing from the document-ingestion track inside the cleanup branch** — no OCR, no parser
improvement, no ground-truth work. Both are wanted, and mixing them here destroys the one property
that makes this cleanup verifiable: if the suite moves, the cause must be unambiguously the
sanitization. A fixture edit and a parser change landing together means neither can be cleared. The
sequence is: security cleanup → green baseline → merge → document intelligence.

**P0 — upload security.** Empty-file, content-type, extension and magic-byte validation in
`StatementUpload`; malformed-PDF, decompression-bomb and page-count protection; parser timeouts;
investigate malware scanning. This is the largest untrusted-input surface and the only row in the
table that is both Missing and directly reachable by an anonymous upload.

**P1 — access and data isolation.** Tenant isolation proven by explicit **positive and negative**
authorization tests; rate limiting verified per endpoint, especially auth and the expensive
import/OCR paths; **enforcement** around masking rather than reliance on a developer remembering to
call it.

**P1 — edge and API security.** Check whether Spring Security already supplies the missing headers
*before* implementing duplicates at the CDN, then add CSP / HSTS / `X-Content-Type-Options` /
`Referrer-Policy` at whichever layer is correct.

## Why document integrity belongs in this document

**Rule, and it is release-blocking rather than advisory.** *Never classify a financial document as
successfully processed merely because the parser produced output. Success requires evidence that the financial entities, transactions, ownership and
totals extracted are consistent enough to trust.*

A parser that silently turns **Savings + RD + FD** into **Savings only** is not a parsing bug with a
UX consequence. It writes incorrect financial state into a financial system, under a success label —
and a wrong balance a user acts on is an integrity failure, not a cosmetic one. That is the same
category as an access-control failure, and it is why `PARSED_COMPLETE`, ground truth and per-section
verification sit alongside masking and rate limiting here rather than in a separate quality backlog.

See [ADR-004](../architecture/adr/adr-004-document-pipeline-scope.md) §3: partial data under a success
label is the one categorically unacceptable outcome, because refusal is visible and silent
misattribution is not.
