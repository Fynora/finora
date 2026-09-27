#!/bin/sh
# Writes the webhook URL from the environment to the file alertmanager.yml reads, then starts
# Alertmanager.
#
# WHY A SCRIPT
# ------------
# Alertmanager does not expand environment variables in its config. The URL is a credential and
# this repository is public, so it cannot be written into alertmanager.yml either. url_file is the
# supported way to keep it out, and this script is what puts the file there.
#
# WHY IT REFUSES TO START WITHOUT ONE
# -----------------------------------
# Started without the variable, Alertmanager would come up healthy, accept every alert from
# Prometheus, and fail to deliver each one. That is exactly the silent state this service exists to
# end. Exiting here makes the missing variable show up as a crashed deploy instead.
set -eu

if [ -z "${ALERT_WEBHOOK_URL:-}" ]; then
  echo "entrypoint: ALERT_WEBHOOK_URL is not set; refusing to start an Alertmanager that cannot deliver" >&2
  exit 1
fi

case "$ALERT_WEBHOOK_URL" in
  http://*|https://*) ;;
  *)
    # The value itself is not echoed: it is a credential.
    echo "entrypoint: ALERT_WEBHOOK_URL must start with http:// or https://" >&2
    exit 1
    ;;
esac

umask 077
printf '%s' "$ALERT_WEBHOOK_URL" > /etc/alertmanager/webhook-url

exec /bin/alertmanager "$@"
