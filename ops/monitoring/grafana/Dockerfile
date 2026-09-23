# Grafana for the production Railway project.
#
# Same argument as railway/prometheus/Dockerfile, which this mirrors: the dashboards and the
# datasource are code in this repository, `allowUiUpdates: false` is set deliberately, and a panel
# edited in the UI is lost on the next restart. Building them into the image keeps what runs
# identical to what was reviewed, rather than depending on someone remembering to re-upload a file.
#
# BUILD CONTEXT
# -------------
# Root Directory = ops/monitoring/grafana, and nothing else to configure. This Dockerfile sits at
# the root of that context, which is where Railway looks by default, so there is no railway.json
# and no Dockerfile-path field for anyone to get wrong.
#
# That is deliberate, and it is why this file is not under railway/ next to the Prometheus one.
# Railway reads railway.json from the service's Root Directory -- so a second service also rooted at
# ops/monitoring would read ops/monitoring/railway.json and build PROMETHEUS, silently, because that
# file names the Prometheus Dockerfile. Rooting Grafana one directory deeper removes the collision
# rather than resolving it with a second config file and a path whose semantics would need testing
# in production to confirm.

FROM alpine:3.20 AS config

# The Prometheus service's Railway private address. Grafana reaches Prometheus over the private
# network; Prometheus has no public domain and must not be given one.
ARG PROMETHEUS_PRIVATE_URL=http://fynora.railway.internal:9090

WORKDIR /render
COPY provisioning ./provisioning
COPY dashboards ./dashboards

# The datasource URL is the only thing that differs from the local stack. Guarded on both sides for
# the same reason the Prometheus image guards its scrape target: a silent no-op sed ships a Grafana
# pointing at a hostname that does not resolve, every panel reads "No data", and that is
# indistinguishable from a product nobody is using -- which is precisely the confusion the
# navigation dashboard's top row exists to prevent.
RUN set -eux; \
    grep -q "http://prometheus:9090" provisioning/datasources/prometheus.yml; \
    sed -i "s|http://prometheus:9090|${PROMETHEUS_PRIVATE_URL}|" provisioning/datasources/prometheus.yml; \
    grep -q "${PROMETHEUS_PRIVATE_URL}" provisioning/datasources/prometheus.yml; \
    ! grep -q "http://prometheus:9090" provisioning/datasources/prometheus.yml; \
    test -f dashboards/navigation-usage.json

FROM grafana/grafana:11.2.0

USER root
COPY --from=config /render/provisioning /etc/grafana/provisioning
COPY --from=config /render/dashboards /var/lib/grafana/dashboards
COPY require-admin-password.sh /usr/local/bin/require-admin-password.sh
RUN chmod 0755 /usr/local/bin/require-admin-password.sh \
    && chown -R 472:0 /etc/grafana/provisioning /var/lib/grafana/dashboards
USER 472

# Security posture, set here rather than left to Railway variables so it cannot be lost by someone
# recreating the service. ops/monitoring/docker-compose.yml enables anonymous viewing on purpose --
# it is a throwaway local stack -- and this is the place that must not inherit that.
#
# The admin PASSWORD is deliberately NOT set here: a password baked into an image is in the registry
# and in this repository's build logs. It arrives as a Railway variable, and
# require-admin-password.sh refuses to start without one.
ENV GF_AUTH_ANONYMOUS_ENABLED=false \
    GF_USERS_ALLOW_SIGN_UP=false \
    GF_SECURITY_ADMIN_USER=admin \
    GF_SECURITY_COOKIE_SECURE=true \
    GF_SECURITY_CONTENT_TYPE_PROTECTION=true \
    GF_ANALYTICS_REPORTING_ENABLED=false \
    GF_ANALYTICS_CHECK_FOR_UPDATES=false

ENTRYPOINT [ "/usr/local/bin/require-admin-password.sh" ]
