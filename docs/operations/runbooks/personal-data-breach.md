# Personal data breach runbook

Privacy workstream 9. What to do when personal data may have been exposed, lost or changed without authorisation. It is written to be followed under pressure: short steps, named owners, and a log kept from the first minute.

**Status: draft for counsel.** Every notification deadline is marked "counsel to confirm": these are the engineering team's reading of the DPDP Rules and CERT-In directions, not legal advice.

## Owners

| Role | Who | Decides |
|---|---|---|
| Incident lead | Sid | Containment, who works on it, when it is closed |
| Legal | Counsel | Whether it is a notifiable breach, and the wording of every notice |
| Grievance Officer | As named in the Privacy Policy | Answers affected users |

## The first hour

1. **Open the log** (template below), in a private document. Write down the time you learned of it, how, and from whom. Every later step gets a line with its time.
2. **Contain without destroying evidence.** Rotate the exposed credential, revoke the session or token, block the IP, take the bad deploy down. Do not delete logs, rows or objects: copy what you need first.
3. **Freeze evidence.** Export the relevant `audit_logs` rows (admin reads are `ADMIN_USER_DATA_VIEWED` and `ADMIN_USER_DATA_LISTED`), the Railway, Cloudflare and Sentry logs for the window, and the provider's own notice if there is one. Note each provider's log retention, since some expire within days.
4. **Tell counsel**, with what is known and what is not.

## Assess (same day)

Answer each question with evidence: a query result, a log line or a provider statement. "Probably" is not an answer.

- Which data? Use the retention register (`docs/security/privacy/retention-register.md`) to list what the affected system holds.
- Whose? A count of affected accounts, and whether real customers are among them (prod has had real customers since 2026-10-11).
- Was it encrypted? Statement files and saved passwords are encrypted by Fynora (`FINORA_ENCRYPTION_KEY`); everything else depends on the provider. If the key itself may be exposed, encryption does not count.
- Was it read, or only reachable? Say which one the evidence shows, or that it is not established.
- Is it still happening?

## Notify (counsel decides; deadlines to confirm)

| To | When (our reading; counsel to confirm) | What |
|---|---|---|
| Data Protection Board of India | Without delay, and a fuller report within 72 hours of becoming aware (DPDP Rules) | Nature, extent, timing, likely impact, measures taken, the Fynora contact |
| Each affected user | Without delay (DPDP Rules) | What happened in plain words, the likely consequences, what Fynora did, what they can do, the contact |
| CERT-In | Within 6 hours of noticing a reportable cyber incident (CERT-In directions, April 2022) | Their form |
| Payment processors / stores | If card or payment data is involved | Per their terms |

Users are sent the email through Resend from a template counsel has approved. Nothing goes out unapproved.

## Close

1. Fix the cause and record the fix (PR links).
2. Re-run the evidence queries and record that the exposure is closed.
3. Write the record, with timeline, data, people affected, decisions, notices sent and fixes, and keep it with the log.
4. Update this runbook with what was learned.

## Log template

| Time (IST) | Who | What happened / what was done | Evidence |
|---|---|---|---|
| | | | |

## Open case

**Railway security incident, 2026-10-21** (workstream 1).
- Railway said customer environment variables, some decrypted, may have been read.
- Several secrets were rotated on 2026-10-23: the Razorpay keys, the R2 keys and, as far as is known, the database password.
- `FINORA_ENCRYPTION_KEY`, `JWT_SECRET` and others were not rotated as of the last record.
- **Counsel has not yet decided whether this was a notifiable breach.** This runbook's assessment steps apply to it.
