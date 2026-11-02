# Retention register

Privacy workstream 9. One row per kind of personal data Fynora keeps: what it is for, how long it is kept, what deletes it, and the legal basis.

- Every "Rule today" was read from code on `main` on 2026-11-02. The Source column names the code.
- The "Legal basis" and "Period to confirm" columns are for counsel. They are left blank wherever no one has decided.
- "Not established" means nobody has checked. It does not mean "none".

The Privacy Policy quotes this register, so a change to a rule here must change the policy in the same PR.

## Account and identity

| Data | Purpose | Rule today | Deleted by | Source | Legal basis | Period to confirm |
|---|---|---|---|---|---|---|
| Name, email, phone, password hash | Account, sign-in, recovery | Until the account is deleted. Then the email is replaced with a placeholder, the name becomes "Deleted User", and the phone and password are erased. | Account purge | `AccountPurgeSweepService.purgeOne` | | |
| Phone number held by Firebase Authentication | Phone verification | Deleted with the account. A daily sweep removes Firebase phone users that no live account names, after 24 hours. | Account purge; phone identity sweep | `PhoneIdentitySweepService` (#2429) | | |
| Deactivated (not deleted) accounts | Self-service return | Kept with no time limit. The reactivation window setting is off. | Nothing | `app.account-lifecycle.reactivation-window-enabled=false` | | **Yes**: a period, with notice before deletion |
| `users.deactivation_reason` | Churn analysis | Kept on the anonymised row after deletion | Nothing | `AccountPurgeSweepService` ("deactivationReason is kept") | | **Yes** |
| Deletion reason (when given) | Product analysis | Month only, no link to the person, kept with no time limit | Nothing | Gate 2a spec §8 (V278) | | |
| Sessions and refresh tokens | Signed-in sessions | 24 h idle, 30 days maximum. Deleted with the account. | Expiry; account purge | Privacy Policy, Data Encryption & Security | | |
| Email sign-in codes | Sign-in | 24 hours | `EmailLoginOtpRetentionSweepService` | code | | |
| Send-a-statement codes | Upload from a computer | 24 hours | `StatementSendCodeRetentionSweep` | code (V293) | | |
| Terms acceptance records | Proof of consent | Kept after account deletion, linked to the anonymised row | Nothing | V302; not in the purge | | **Yes** (counsel item from WS7/8) |
| AI consent answer and its history | Proof of consent | The answer is on the user row; each change is an audit row (see Audit logs) | As audit logs | `TermsConsentService` | | |
| Sign-up source (referrer host, utm tags, first page) | Learn what brings people | Deleted with the account | Account purge | V286, V300 | | |

## Financial data

| Data | Purpose | Rule today | Deleted by | Source | Legal basis | Period to confirm |
|---|---|---|---|---|---|---|
| Transactions, budgets, goals, categories, rules | The product | Until the account is deleted | Account purge | `AccountPurgeSweepService` | | |
| Bank accounts the user added | The product | Until deleted. On account deletion the row stays for statement bookkeeping, with name, holder name, masked number, branch, IFSC and identity hash cleared. | Account purge | `AccountPurgeSweepService`; V304 (#2471) | | |
| Statement file (confirmed import) | Re-download, refresh | Until the statement is deleted, then 90 days, then the storage sweep | `StatementStorageSweepService` | `app.statement-storage.sweep.retention-days=90` | | |
| Statement file name, holder name, layout (deleted statement) | none after deletion | Cleared when the statement is deleted | `StatementImport` `@SQLDelete` | #2428, V298 | | |
| Upload waiting for import (`import_sessions`) | Review before import | 48 hours, longer while held for trust review. Encrypted since V304. | `ImportSessionService.sweepExpiredSessions` | code; #2471 | | |
| Uploaded file of a completed or cancelled import job | Async import | 90 days after the job finished | `StatementStorageSweepService` (import-jobs source) | code | | |
| Uploaded file of a failed import | Retry without re-upload | Until the account is deleted | Account purge | Privacy Policy, Data Retention; no sweep | | **Yes**: proposed N days |
| Import job rows (file name) | Import history | Until the account is deleted, including after the statement is deleted | Account purge | #2428 "Not in this PR" | | **Yes** |
| Saved statement passwords | Refresh a locked PDF | Until withdrawn, or the statement or account is deleted | `StatementPasswordService` | code | | |
| Upload attempt records (`statement_analysis_sessions`) | Find and fix import problems | Kept with no time limit. On account deletion the link, file name and reason text are removed. | Account purge (anonymise) | `StatementAnalysisSessionRepository.anonymizeByUserId` | | **Yes** |

## AI

| Data | Purpose | Rule today | Deleted by | Source | Legal basis | Period to confirm |
|---|---|---|---|---|---|---|
| Ask Fyn conversations | Scroll-back; Free daily count | Until the account is deleted. Kept when AI is switched off. | Account purge | `TermsConsentService`; purge | | **Yes** (counsel item from WS7/8) |
| AI insight texts | Show the story / "Why?" | Until AI is switched off or the account is deleted | `TermsConsentService.eraseAiOutput`; purge | V283, V302 | | |
| AI request log: model, cost, time | Cost caps, safety | Until the account is deleted | Account purge | `ai_audit_log` | | |
| AI request log: figures sent and returned | Safety review | Cleared when AI is switched off; deleted with the account | `TermsConsentService.eraseAiOutput`; purge | V302 | | |
| Shared category observations | Category suggestions | 180 or 365 days for one or two voters. No deletion date once shared, or with three or more voters. A user's own rows are deleted with the account. | `SharedCorpusRetentionSweepService`; purge | Privacy Policy | | **Yes**: the "no date" cases |
| Shared suggestions, merchant understandings | Category suggestions | No deletion date; not removed with an account | Nothing | Privacy Policy; `MerchantUnderstandingService` | | **Yes** |

## Usage and security records

| Data | Purpose | Rule today | Deleted by | Source | Legal basis | Period to confirm |
|---|---|---|---|---|---|---|
| Days the app was used | Usage analysis | Deleted with the account | Account purge | V249 | | |
| Import-funnel dates and "what stopped you" answers | Import analysis | Deleted with the account | Account purge | V275, V276 | | |
| Audit logs | Security, evidence | Row kept forever; details cleared after 730 days (90-day floor in code) | `AuditService.redactExpiredMetadata` | `app.audit.redaction.retention-days=730` | | **Yes**: the DPDP Rules' log minimum (my reading: one year) |
| Admin reads of customer data | Insider-misuse review | As audit logs | As audit logs | WS9 (`AdminReadAuditPolicy`) | | |
| Free converter: IP address | Rate limit | Up to 24 hours | code | Privacy Policy, converter section | | |
| Webhook payloads (Razorpay, RevenueCat) | Billing | Not established | Not established | — | | **Yes** |
| Application logs (Railway), error reports (Sentry) | Operations | Not established: depends on each provider's console settings | Provider | Sid to read from each console | | **Yes** |
| Database backups | Disaster recovery | Point-in-time recovery is on; the window is not established | Provider | Railway console | | **Yes**: state a number in the policy |

## Proposed changes (for Sid and counsel)

Each needs a decision, then a sweep with an integration test at the boundary: one day before, on, and one day after the cutoff (plan §6).

1. **Deactivated accounts:** delete after N months, with notice 48 hours before.
2. **Failed-import files:** delete after N days instead of at account deletion.
3. **Import job file names:** clear them when the statement is deleted.
4. **Upload attempt records:** set an age limit.
5. **The "no deletion date" shared rows:** decide whether they are personal data at all.
