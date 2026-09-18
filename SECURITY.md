# Security Policy

Finora is a personal financial operating system that processes bank statements and other
sensitive financial data. We take security seriously and appreciate responsible disclosure of
any vulnerabilities.

## Supported Versions

Finora is deployed continuously from the `main` branch — there is no versioned release line.
Only `main` is supported; a fix lands as a new commit rather than a backport.

## Reporting a Vulnerability

**Please do not open a public GitHub issue for security vulnerabilities.**

Report privately using GitHub's [Private Vulnerability Reporting](https://github.com/Fynora/finora/security/advisories/new)
feature (Security tab → "Report a vulnerability"). This opens a private advisory visible only to
the maintainer until a fix is ready.

Please include:
- A description of the vulnerability and its potential impact
- Steps to reproduce (a minimal example if possible)
- Any relevant logs, requests, or screenshots

## What to Expect

- Acknowledgement within 5 business days
- An assessment of severity and affected scope
- A fix timeline appropriate to severity — critical issues (auth bypass, data exposure across
  users, RCE) are prioritized immediately
- Credit in the fix's commit/PR description, if desired

## Scope

In scope: the backend API, web frontend, admin portal, and mobile app in this repository.

Out of scope: third-party services Finora integrates with (Railway, Cloudflare, Firebase,
Google/Apple OAuth) — report those directly to the respective provider.

## Automated Security Tooling

This repository already runs:
- **Dependabot** — scheduled dependency updates ([`.github/dependabot.yml`](.github/dependabot.yml))
  plus vulnerability alerts and automated security-fix PRs for known CVEs across Maven, npm, and
  Docker dependencies
- **Secret scanning with push protection** — blocks commits containing recognizable credential
  patterns before they reach the repository
- **[gitleaks](https://github.com/gitleaks/gitleaks)** — complementary to push protection, not a
  replacement: a broader, entropy-based scan on every PR (that PR's diff) and nightly (full commit
  history), plus a best-effort local pre-commit check when the binary is installed. Known
  historical false positives are recorded in [`.gitleaksignore`](.gitleaksignore) by fingerprint.
- **[`scripts/check-dependency-advisories.py`](scripts/check-dependency-advisories.py)** — gates
  CI on npm advisories in shipped frontend/admin-portal/mobile code, with a maintained allowlist
- **Container image hygiene** — the backend image's base images are pinned by digest in
  [`backend/Dockerfile`](backend/Dockerfile) (Dependabot's `docker` ecosystem proposes updates to
  tag and digest together), CI builds the image and checks its runtime contract
  ([`scripts/check-backend-image.sh`](scripts/check-backend-image.sh)) whenever the Dockerfile, its
  entrypoint or `pom.xml` changes, and a nightly
  [Trivy scan](.github/workflows/image-scan-nightly.yml) reports fixable HIGH/CRITICAL
  vulnerabilities in the image's OS packages. Exceptions are recorded in
  [`.trivyignore`](.trivyignore), each with a reason and a review date.

See [`docs/quality/tooling/ENGINEERING_TOOLING_ROADMAP.md`](docs/quality/tooling/ENGINEERING_TOOLING_ROADMAP.md)
for planned additions (Semgrep, OWASP ZAP).
