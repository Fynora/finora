# Alertmanager for the production Railway project.
#
# BUILD CONTEXT
# -------------
# Root Directory = ops/monitoring/alertmanager, and nothing else to configure. This follows the same
# reasoning as grafana/Dockerfile: a service rooted at ops/monitoring would read
# ops/monitoring/railway.json, which names the Prometheus Dockerfile. So it would silently build
# Prometheus. Keeping this Dockerfile at the root of its own directory removes that collision.
#
# REQUIRED RAILWAY VARIABLE
# -------------------------
# ALERT_WEBHOOK_URL is the Better Stack Prometheus integration's webhook URL. entrypoint.sh refuses
# to start without it. See alertmanager.yml for why this is a webhook and not email.

FROM prom/alertmanager:v0.27.0

COPY alertmanager.yml /etc/alertmanager/alertmanager.yml

# Validate at build time, so a broken routing file fails the deploy rather than a restart loop.
RUN ["/bin/amtool", "check-config", "/etc/alertmanager/alertmanager.yml"]

USER root
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 0755 /usr/local/bin/entrypoint.sh
# The base image runs as nobody and /etc/alertmanager is already owned by nobody, so entrypoint.sh
# can write the URL file without root. Only the chmod above needed privilege.
USER nobody

ENTRYPOINT [ "/usr/local/bin/entrypoint.sh" ]

# --web.listen-address=:9093 binds every address, IPv6 included. Railway's private network is
#   IPv6-only, and Prometheus reaches this service over it.
# --cluster.listen-address= (empty) turns off high-availability gossip. This is a single instance,
#   so gossip has no peer to talk to. With gossip on, Alertmanager also has to find an address to
#   advertise at startup. Whether that works on Railway's network has not been tested, and turning
#   gossip off means it never has to.
# No volume. /alertmanager holds silences and the notification log. Losing them on a redeploy costs
#   at most one repeated notification, and there is no silence worth preserving yet.
CMD [ "--config.file=/etc/alertmanager/alertmanager.yml", \
      "--storage.path=/alertmanager", \
      "--web.listen-address=:9093", \
      "--cluster.listen-address=" ]
