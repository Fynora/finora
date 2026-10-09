#!/usr/bin/env python3
"""Fails if the commit just pushed to main did not get a full, passing CI run before it merged.

WHY THIS EXISTS
---------------
While the repository is private, CI runs in its lean profile (docs/architecture/infrastructure/
ci-visibility-profiles.md): a push to main runs only repo-hygiene, and a pull request's CI jobs are
all skipped unless the PR carries the `full-ci` label. So the only full test a change gets is the
labelled PR run before merging -- and nothing noticed when a PR merged without one. Railway deploys
main the moment a commit lands, so an untested merge goes straight to production with no red mark
anywhere. This check is that red mark: ci.yml's repo-hygiene job runs it on every private push to
main, and a failure there turns main's CI run red and notifies whoever merged.

WHAT COUNTS AS "TESTED"
-----------------------
The merged PR's FINAL commit (its head when it merged) must have a ci.yml pull_request run whose
LATEST attempt finished with conclusion `success`, AND in which the `Detect changed areas` job
actually ran and succeeded. That job is the marker for a full run: in the private profile it runs
only for a `full-ci` PR (or by hand), and every other job's path filtering hangs off it. A lean run
also ends `success` -- skipped jobs count as passing -- which is exactly why conclusion alone is not
evidence. Only the latest run on that commit counts, so a full run followed by a newer lean one
(e.g. after the label was removed) does not pass, and neither does a run still going at merge time.

It fails, with the reason, when:
  - no merged pull request produced this commit (a direct push to main -- possible while private,
    because GitHub Free does not enforce rulesets on private repositories);
  - the PR's final commit has no ci.yml pull_request run at all;
  - that commit's latest run had not finished, or finished other than `success`;
  - that run was the lean profile (Detect changed areas did not run).

Usage (CI passes nothing; GITHUB_SHA, GITHUB_REPOSITORY and GH_TOKEN come from the runner):
  python3 scripts/check-merge-was-tested.py [--sha SHA] [--repo OWNER/NAME] [--pr NUMBER]
--pr skips the commit->PR lookup; it exists for checking a specific PR by hand.
"""

import argparse
import json
import os
import subprocess
import sys

MARKER_JOB = "Detect changed areas"
REMEDY = ("Test main now: `gh workflow run ci.yml --ref main` (or the Actions tab -> CI -> "
          "Run workflow) and fix anything it finds before the next merge. Once that run passes "
          "on this commit, re-run this failed job and it turns green.")


def gh(*args):
    out = subprocess.run(["gh", *args], capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(f"gh {' '.join(args)} failed: {out.stderr.strip()}")
    return json.loads(out.stdout or "null")


def fail(message):
    print(f"::error::{message}")
    print(REMEDY)
    return 1


# Pinned, not left to gh's default. REST API version 2026-03-10 removes `merge_commit_sha` from
# pull request responses, naming GET /repos/{owner}/{repo}/commits/{commit_sha}/pulls among the
# affected endpoints (GitHub's breaking-changes changelog). gh selected 2022-11-28 when this was
# written (2026-10-04, X-GitHub-Api-Version-Selected); if a gh upgrade ever moved its default, every
# merge would silently stop matching and read as a direct push.
API_VERSION = ["-H", "X-GitHub-Api-Version: 2022-11-28"]


def merged_pr_for(repo, sha):
    prs = [p for p in gh("api", *API_VERSION, f"repos/{repo}/commits/{sha}/pulls") or []
           if p.get("merged_at")]
    exact = [p for p in prs if p.get("merge_commit_sha") == sha]
    if exact:
        return exact[0]
    # Only if the field is gone altogether (that API version, once 2022-11-28 is retired): the
    # single merged PR into main this commit belongs to. More than one is ambiguous -- no match.
    if prs and all("merge_commit_sha" not in p for p in prs):
        into_main = [p for p in prs if p.get("base", {}).get("ref") == "main"]
        if len(into_main) == 1:
            return into_main[0]
    return None


def full_run_on(repo, sha, event):
    """The latest ci.yml run of `event` on `sha`, if it finished `success` with the marker job
    actually run -- the same test of "full and passing" the PR path below applies."""
    runs = gh("run", "list", "-R", repo, "--workflow", "ci.yml", "--event", event,
              "--commit", sha, "--limit", "50", "--json", "databaseId,status,conclusion,createdAt,url")
    if not runs:
        return None
    latest = max(runs, key=lambda r: r["createdAt"])
    if latest["status"] != "completed" or latest["conclusion"] != "success":
        return None
    jobs = gh("run", "view", str(latest["databaseId"]), "-R", repo, "--json", "jobs")["jobs"]
    if any(j["name"] == MARKER_JOB and j["conclusion"] == "success" for j in jobs):
        return latest
    return None


def tested_on_main_afterwards(repo, sha):
    """A by-hand or weekly full run on this exact main commit, passing. It is what REMEDY asks
    for, so once it exists, re-running this check clears main's red mark -- otherwise the remedy
    could never turn this step green, and a red main nobody can clear is one people learn to
    ignore."""
    for event in ("workflow_dispatch", "schedule"):
        run = full_run_on(repo, sha, event)
        if run:
            return run
    return None


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--sha", default=os.environ.get("GITHUB_SHA"))
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY"))
    ap.add_argument("--pr", type=int)
    args = ap.parse_args()
    if not args.repo or (not args.sha and args.pr is None):
        ap.error("need --repo and --sha (or GITHUB_REPOSITORY/GITHUB_SHA), or --pr")

    if args.pr is not None:
        pr = gh("api", *API_VERSION, f"repos/{args.repo}/pulls/{args.pr}")
    else:
        later = tested_on_main_afterwards(args.repo, args.sha)
        if later:
            print(f"{args.sha[:9]} was tested in full on main itself after it landed: "
                  f"{later['url']}")
            return 0
        pr = merged_pr_for(args.repo, args.sha)
        if pr is None:
            return fail(f"{args.sha[:9]} on main did not come from a merged pull request -- a "
                        "direct push, so no PR run ever tested it.")

    number, head = pr["number"], pr["head"]["sha"]
    runs = gh("run", "list", "-R", args.repo, "--workflow", "ci.yml", "--event", "pull_request",
              "--commit", head, "--limit", "50",
              "--json", "databaseId,status,conclusion,createdAt,url")
    if not runs:
        return fail(f"PR #{number} merged with no CI run on its final commit {head[:9]}.")
    latest = max(runs, key=lambda r: r["createdAt"])
    where = f"PR #{number}'s last CI run on {head[:9]} ({latest['url']})"
    if latest["status"] != "completed":
        return fail(f"{where} was still {latest['status']} when it merged -- merged before its "
                    "tests finished.")
    lean = (f"-- the PR merged without a full run. While the repository is private, a PR needs "
            "the `full-ci` label and a push before merging.")
    # A run whose every job was skipped -- the private profile's ordinary PR run -- ends with
    # conclusion `skipped`, not `success` (seen on Dependabot #2017, 2026-10-04).
    if latest["conclusion"] == "skipped":
        return fail(f"{where} skipped every job {lean}")
    if latest["conclusion"] != "success":
        return fail(f"{where} ended {latest['conclusion']} -- merged with failing CI.")

    jobs = gh("run", "view", str(latest["databaseId"]), "-R", args.repo, "--json", "jobs")["jobs"]
    marker = [j for j in jobs if j["name"] == MARKER_JOB]
    if not marker or marker[0]["conclusion"] != "success":
        state = marker[0]["conclusion"] if marker else "absent"
        return fail(f"{where} was the lean profile (`{MARKER_JOB}`: {state}) {lean}")

    print(f"PR #{number} had a full, passing CI run on its final commit {head[:9]}: "
          f"{latest['url']}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except RuntimeError as exc:
        print(f"::error::check-merge-was-tested could not complete: {exc}")
        sys.exit(1)
