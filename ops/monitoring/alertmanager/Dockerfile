# Alertmanager for the production Railway project.
#
# BUILD CONTEXT
# -------------
# Root Directory = ops/monitoring/alertmanager, and nothing else to configure. This follows the same
# reasoning as grafana/Dockerfile: a service rooted at ops/monitoring would read
# ops/monitoring/railway.json, which names the Prometheus Dockerfile. So it would silently build
# Prometheus. Keeping this Dockerfile at the root of its own directory removes that collision.
#
# RAILWAY VARIABLES
# -----------------
# Required: ALERT_EMAIL_TO (who gets the alerts) and ALERT_SMTP_PASSWORD (a Resend API key).
# The defaults below cover everything else. entrypoint.sh refuses to start without the required
# two. See alertmanager.yml for why this is email through Resend.

FROM prom/alertmanager:v0.27.0

# Resend's SMTP relay. Port 587 is STARTTLS, and the username is literally "resend". The sender must
# be on a domain verified in Resend. fynora.net is the domain the backend already sends from.
ENV ALERT_SMTP_SMARTHOST=smtp.resend.com:587 \
    ALERT_SMTP_USERNAME=resend \
    ALERT_SMTP_REQUIRE_TLS=true \
    ALERT_EMAIL_FROM="Fynora Alerts <alerts@fynora.net>"

COPY alertmanager.yml /etc/alertmanager/alertmanager.yml

USER root
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 0755 /usr/local/bin/entrypoint.sh
# The base image runs as nobody and /etc/alertmanager is already owned by nobody, so entrypoint.sh
# can write the rendered config and the password file without root. Only the chmod needed privilege.
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
CMD [ "--config.file=/etc/alertmanager/rendered.yml", \
      "--storage.path=/alertmanager", \
      "--web.listen-address=:9093", \
      "--cluster.listen-address=" ]
