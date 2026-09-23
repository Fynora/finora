# Operational monitoring

Prometheus scrape config, alert rules and Grafana dashboards for Finora — as code, reviewable, and
runnable locally against a real backend.

Engineering context lives in [`docs/engineering/observability.md`](../../docs/engineering/observability.md).
This file is how to run it.

---

## What is here

| File | Purpose |
|---|---|
| `prometheus.yml` | Scrape config. Reads a bearer token from a gitignored file. |
| `alerts.yml` | 10 alert rules, every one on a sustained condition. |
| `grafana/dashboards/worker-health.json` | Worker + queue + infrastructure dashboard. |
| `grafana/dashboards/reconciliation.json` | Reconciliation transfer-matching and duplicate-override counters (measurement only, no alerts yet). |
| `grafana/provisioning/` | Datasource and dashboard provisioning, so the stack works on first run. |
| `docker-compose.yml` | Local Prometheus + Grafana for validating the above. |

**This is not a production deployment.** Production Prometheus and Grafana are infrastructure
decisions — retention, HA, auth, network placement — and belong outside this repository. What is
here is the configuration those instances should load, in a form that can be reviewed in a pull
request and proven to work before it is relied upon.

---

## External monitoring

Production availability is monitored externally through **Better Stack**, using an HTTP(S) monitor
against `https://api.fynora.net/health`. The monitor expects HTTP `200` and a body containing
`"status":"UP"` — a status-code-only check would pass against a misconfigured proxy or a maintenance
page serving the wrong body, so it checks both.

> **This path changed.** It was `/actuator/health` until actuator moved to its own private port.
> `/health` (`HealthController`) reports the same aggregate from the same `HealthEndpoint`, so the
> `200` + `"status":"UP"` contract is unchanged — but **the monitor's URL has to be updated in the
> Better Stack dashboard**, and it is not configured from this repository. Until someone does that,
> the monitor reports a permanent false "down". Railway's own deploy healthcheck, `docker-compose`
> and four CI readiness probes were all pointed at the same path and are updated in-repo.

This is deliberately the only endpoint monitored externally. `/api/v1/**` requires authentication,
and everything under `/actuator` is no longer routable from the internet at all (see
[Security](#security)) — pointing an external monitor at any of them would report a permanent false
"down" against a control that is working exactly as designed.

No Better Stack credentials, API keys, or other provider-specific configuration live in this
repository — the monitor is configured directly in Better Stack's dashboard, not as code here.

---

## Running it locally

No token, no setup step. The scrape is open on the backend's management port, which is why
[Security](#security) is worth reading before changing anything here.

```bash
# 1. Start the backend (from the repo root)
cd backend && ./mvnw spring-boot:run

# 2. Start the stack
docker compose -f ops/monitoring/docker-compose.yml up
```

- Prometheus — http://localhost:9090 (check **Status → Targets** first)
- Grafana — http://localhost:3001, dashboard under the **Finora** folder

### Generating worker activity to look at

An empty dashboard proves nothing. Confirm a merchant category in the app — that enqueues a
`merchant_learning_events` row, which the worker picks up within 30s — and watch
`finora_worker_completed_total` and `finora_worker_queue_depth` move.

To see the failure paths, the fastest route is a unit test rather than production-shaped data:
`WorkerObservabilityTest` drives every lifecycle event, and `WorkerMetricsExportIT` asserts they
reach a scrape.

### If the target is DOWN

Almost always one of two things:

1. **Backend not reachable at `host.docker.internal:9091`.** Note the port — 9091 is
   `management.server.port`, not `server.port`. Port 8080 answers `404` for `/actuator/prometheus`,
   which is the whole design and not a bug. On Linux the compose file declares `host-gateway`;
   check the backend is bound to `0.0.0.0` rather than `127.0.0.1`.
2. **The backend refused to start.** `ManagementPortSeparationGuard` fails the boot if actuator
   ends up on the application port. The exception says so explicitly — read the backend's own log
   rather than debugging from Prometheus' side.

Check Prometheus' **Error** column, which shows the status code: a `404` means you are scraping the
application port, and a `401` means something has put actuator back behind the main security chain.

---

## Security

`/actuator/prometheus` is **unauthenticated, on a port the internet cannot reach**. Those two facts
are one control, and neither is safe alone.

This document used to describe option 1 of three as the preferred production posture. That is what
is now implemented:

| | Before | Now |
|---|---|---|
| Application port (`8080`, public) | serves `/actuator/**` behind auth | `/actuator/**` **not mapped** — `404` |
| Management port (`9091`, private) | did not exist | serves `health` + `prometheus`, no credential |
| Public liveness | `/actuator/health` | `/health` (`HealthController`) |
| What protects the scrape | a 15-minute user JWT | no Railway domain routes to `9091` |

**Why the change.** Authentication was the right posture and had one fatal practical problem: the
only credentials this system issues are user access tokens expiring in fifteen minutes, and there is
no service principal to mint one for. The endpoint was secure and unscrapeable simultaneously, which
is why no production dashboard existed. A hand-minted user token was never acceptable in production
— it carries a real user's authorities and expires on that user's schedule.

**What holds it up.** Both public Railway domains (`api.fynora.net` and the generated
`*.up.railway.app`) are pinned to target port `8080`; nothing routes to `9091`. Verified against the
Railway dashboard, not assumed.

**What enforces it.**

- `ManagementPortIsolationIT` — asserts `404` on the application port *and* an anonymous `200` on
  the management port. Both directions, because each fails silently on its own: the first publishes
  the scrape, the second empties every dashboard, and an empty panel looks exactly like a quiet
  system.
- `ManagementPortSeparationGuard` — refuses to finish booting if actuator lands on the application
  port, whether from `MANAGEMENT_SERVER_PORT` being unset or set to `8080`. A single environment
  variable is otherwise enough to publish the scrape with nothing logged and no test failing.

**If you add a public domain to this service, do not point it at `9091`.** That single action
undoes all of the above, and nothing in this repository can stop it.

---

## Deploying this to production

Prometheus and Grafana themselves are still infrastructure, not code in this repository — retention,
HA and auth are deployment decisions. What changed is that the hard part is now solved: a Prometheus
running **as a service inside the same Railway project** can scrape the backend with no credential.

Sketch, not a tested recipe:

1. Add a Prometheus service to the Railway project. Give it no public domain.
2. Point its scrape target at the backend's private address on the management port —
   `${RAILWAY_PRIVATE_DOMAIN}:9091`, in the shape `prometheus.yml` already carries as a comment.
3. Give it a volume; a Prometheus without one loses its history on every redeploy, which defeats the
   point of collecting a baseline.
4. Add Grafana the same way, with `ops/monitoring/grafana/provisioning` mounted, so the dashboards
   in this directory are what it loads.

**On IPv6.** Railway's private networking is IPv6-only, so the management listener has to accept
IPv6 for a private scrape to connect. The backend does not set `management.server.address`, and the
framework default was measured rather than assumed: booting the jar with default settings binds
`*:9091` as an IPv6 dual-stack wildcard, the same way the application port binds `*:8080` — and that
port demonstrably works on Railway today.

That is strong evidence, not proof: it was measured on macOS, and a Linux container with
`net.ipv6.bindv6only=1` would behave differently. If the Prometheus target does not come up on the
first deploy, set `MANAGEMENT_SERVER_ADDRESS=::` — but check the target list before assuming that is
the cause.

---

## Changing a dashboard or an alert

Edit the file, not the Grafana UI. `allowUiUpdates: false` is set deliberately: a panel edited in
the UI is lost on the next container restart, and a query nobody reviewed is how a dashboard ends up
quietly showing the wrong series.

`scripts/check-dashboard-metrics.py` runs in CI and pre-commit, and fails if a query references a
metric the framework never emits. It exists because **an empty panel and a healthy system look
identical** — and because Micrometer renames on the way out, so the string a dashboard needs is
never the string in the Java source:

| In Java | On the scrape |
|---|---|
| `finora.worker.dead_letters` (counter) | `finora_worker_dead_letters_total` |
| `finora.worker.duration` (timer) | `finora_worker_duration_seconds_bucket` / `_count` / `_sum` |
| `finora.worker.oldest_pending_age` (gauge, `baseUnit("seconds")`) | `finora_worker_oldest_pending_age_seconds` |

---

## Alert thresholds are starting points, not measurements

Finora has no production traffic history, so any threshold claiming to be tuned would be invented.
They are set where a human would want to *look*, not where a human would want to be *woken*, and
should be revised against a real baseline.

The one exception is `QueueAgeExceedsSla`, which maps to a product promise — a confirmed
categorisation takes effect promptly — rather than to a rate nobody has measured.

Every rule has a `for:` clause. A rule without one fires on a scrape blip, and an alert that cries
wolf is worse than no alert: it trains people to close the tab.
