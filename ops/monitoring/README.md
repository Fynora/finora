# Operational monitoring

Prometheus scrape config, alert rules and Grafana dashboards for Finora — as code, reviewable, and
runnable locally against a real backend.

Engineering context lives in [`docs/engineering/observability.md`](../../docs/engineering/observability.md).
This file is how to run it.

---

## What is here

| File | Purpose |
|---|---|
| `prometheus.yml` | Scrape config. No credential — it reaches the backend's private management port. |
| `alerts.yml` | 10 alert rules, every one on a sustained condition. |
| `grafana/dashboards/worker-health.json` | Worker + queue + infrastructure dashboard. |
| `grafana/dashboards/reconciliation.json` | Reconciliation transfer-matching and duplicate-override counters (measurement only, no alerts yet). |
| `grafana/dashboards/auth.json` | Session and authentication counters. |
| `grafana/dashboards/navigation-usage.json` | Navigation usage baseline. Generated — see below. |
| `grafana/provisioning/` | Datasource and dashboard provisioning, so the stack works on first run. |
| `docker-compose.yml` | Local Prometheus + Grafana for validating the above. |

`navigation-usage.json` is the one dashboard here that is generated rather than hand-written, by
`scripts/build-nav-dashboard.py`. Its panels are repetitive and its grid coordinates have to stay
consistent, and Grafana does not reject an overlapping layout — it silently relays the panels, so
the dashboard still provisions and still renders, just not where the file says. The generator
asserts the layout instead. Edit the script, run it, commit both.

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

Prometheus runs as a Railway service in the same project, with no public domain, scraping the
backend over the private network. `railway/prometheus/Dockerfile` builds it **from this directory's
own `prometheus.yml` and `alerts.yml`** — there is no second copy to drift, and the two
Railway-specific values are substituted at build time with guards that fail the build rather than
ship a silently wrong target.

### Service settings

| Setting | Value |
|---|---|
| Root Directory | `ops/monitoring` |
| Dockerfile Path | `railway/prometheus/Dockerfile` |
| Public domain | **none — do not generate one** |
| Volume mount path | `/prometheus` |

Three of those four are load-bearing, and each fails differently:

- **No public domain.** The scrape is served without a credential because the internet cannot reach
  it. A domain on this service publishes queue depths, error rates and JVM internals — and unlike
  the backend, nothing here would refuse to start.
- **A volume at `/prometheus`.** Without one, every redeploy starts an empty database. A baseline
  that cannot survive a deploy is the precise problem this whole exercise exists to fix.
- **Root Directory `ops/monitoring`.** The Dockerfile copies `prometheus.yml` from its build
  context. Point the context at the repo root and the build fails; point it at
  `railway/prometheus` and it fails too.

Retention is set to 90 days in the Dockerfile. Prometheus' own default is **15 days**, which would
delete the start of a four-week window while it was still being collected, leaving no error and no
gap in the graph to show for it.

### Verifying it

```bash
# What the image will actually scrape -- no need to start it
docker run --rm --entrypoint cat <image> /etc/prometheus/prometheus.yml | grep targets:

# Config and alert rules parse
docker run --rm --entrypoint promtool <image> check config /etc/prometheus/prometheus.yml
```

Once deployed, the target list is the only thing worth checking: `Status -> Targets` in the
Prometheus UI, reached through `railway run` or a temporary port-forward rather than a domain.

**The one thing a local build cannot prove** is that the backend's management port is reachable
across Railway's private network, which is IPv6-only. The listener itself is confirmed to bind in
production — `ManagementPortSeparationGuard` refuses to start otherwise, and the backend is
serving — so if the target comes up DOWN, the bind address is the first suspect: set
`MANAGEMENT_SERVER_ADDRESS=::` on the backend service.

### Grafana

Not deployed yet. Prometheus alone starts the baseline accumulating, which is the time-critical
part; the dashboards in `grafana/dashboards/` are provisioned from files and can be pointed at it
whenever Grafana follows.

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
