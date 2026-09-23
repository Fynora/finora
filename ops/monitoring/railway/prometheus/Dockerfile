# Prometheus for the production Railway project.
#
# WHY A DOCKERFILE AND NOT THE STOCK IMAGE
# ----------------------------------------
# The stock prom/prometheus image needs its config from somewhere, and the only somewhere Railway
# offers is a volume -- a file nobody reviews, edited in place, lost on the next volume change.
# This repository's whole position on monitoring config is the opposite of that: dashboards and
# alert rules are code, `allowUiUpdates: false` is set deliberately, and a query nobody reviewed is
# how a dashboard ends up quietly showing the wrong series. The scrape config gets the same
# treatment. Building it into an image keeps the running config identical to the reviewed one, by
# construction rather than by discipline.
#
# The config is rendered at BUILD time, in the stage below, rather than by an entrypoint script.
# That keeps the final image free of any shell dependency, and makes the rendered config immutable:
# what shipped is what runs, and `docker run --rm <image> cat /etc/prometheus/prometheus.yml` shows
# it without starting anything.
#
# BUILD CONTEXT
# -------------
# Expects `ops/monitoring` as its context, so it can copy the same prometheus.yml and alerts.yml the
# local validation stack uses. On Railway that means: Root Directory = ops/monitoring, Dockerfile
# Path = railway/prometheus/Dockerfile. There is deliberately no second copy of either file.

FROM alpine:3.20 AS config

# The backend's Railway private address. Private networking is the entire security model here --
# see ops/monitoring/README.md, "Security" -- so this must stay a *.railway.internal name. Pointing
# it at a public domain would send the scrape over the internet to an endpoint that answers 404
# there anyway.
ARG BACKEND_PRIVATE_DOMAIN=confident-wonder.railway.internal

# management.server.port, NOT server.port. 8080 answers 404 for /actuator/prometheus by design.
ARG MANAGEMENT_PORT=9091

# Stamped on every series so one Prometheus can hold more than one deployment without them
# colliding. Matches the backend's own management.metrics.tags.environment and sentry.environment:
# an alert, an error and a dashboard should agree about which deploy they mean.
ARG ENVIRONMENT=production

WORKDIR /render
COPY prometheus.yml alerts.yml ./

# Two substitutions, and only two -- everything else (scrape_interval, metrics_path, rule_files) is
# identical to what runs locally, which is the point of building from the same file.
#
# The guards are not decoration. A silent no-op sed is exactly how this would ship
# scraping `host.docker.internal` from inside Railway: the build succeeds, the image runs, the
# target is permanently down, and every dashboard looks like an idle system. Fail the build instead.
#
# The last guard reads only `targets:` lines, not the whole file: prometheus.yml also mentions
# host.docker.internal in a comment explaining the local setup, and a whole-file check fails on
# that comment -- which it did, on the first build of this image.
RUN set -eux; \
    grep -q "host.docker.internal:9091" prometheus.yml; \
    grep -q "environment: local" prometheus.yml; \
    sed -i "s|host\.docker\.internal:9091|${BACKEND_PRIVATE_DOMAIN}:${MANAGEMENT_PORT}|" prometheus.yml; \
    sed -i "s|environment: local|environment: ${ENVIRONMENT}|" prometheus.yml; \
    grep -q "${BACKEND_PRIVATE_DOMAIN}:${MANAGEMENT_PORT}" prometheus.yml; \
    grep -q "environment: ${ENVIRONMENT}" prometheus.yml; \
    ! grep -E "^[[:space:]]*-[[:space:]]*targets:" prometheus.yml | grep -q "host.docker.internal" 

FROM prom/prometheus:v2.54.1

COPY --from=config /render/prometheus.yml /etc/prometheus/prometheus.yml
COPY --from=config /render/alerts.yml /etc/prometheus/alerts.yml

# Retention is the one setting that cannot be got wrong quietly. The baseline this exists to collect
# must span at least four weeks including a complete month-end, and Prometheus' DEFAULT retention is
# 15 DAYS -- which would delete the beginning of the window while it was still being collected, with
# no error and no gap in the graph to show for it. 90 days leaves room for the window plus the
# after-period it gets compared against.
#
# --storage.tsdb.path is the default, restated because it is the path the Railway volume must mount
# at. Without a volume every redeploy starts an empty database, and a baseline that cannot survive a
# deploy is the exact problem this whole exercise exists to fix.
CMD [ "--config.file=/etc/prometheus/prometheus.yml", \
      "--storage.tsdb.path=/prometheus", \
      "--storage.tsdb.retention.time=90d", \
      "--web.enable-lifecycle" ]
