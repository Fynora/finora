#!/bin/sh
# Fills alertmanager.yml's placeholders from the environment, writes the SMTP password to its own
# file, checks the result, and starts Alertmanager.
#
# WHY A SCRIPT
# ------------
# Alertmanager does not expand environment variables in its config. The recipient address is
# personal and the SMTP password is a credential, and this repository is public, so neither can be
# written into alertmanager.yml. This script is where they come in.
#
# WHY IT REFUSES TO START ON A BAD VALUE
# --------------------------------------
# Started without a recipient or a password, Alertmanager would come up healthy, accept every alert
# from Prometheus, and fail to deliver each one. That is exactly the silent state this service
# exists to end. Exiting here turns a missing variable into a crashed deploy that someone sees.
set -eu

TEMPLATE=/etc/alertmanager/alertmanager.yml
RENDERED=/etc/alertmanager/rendered.yml

fail() {
  echo "entrypoint: $1" >&2
  exit 1
}

# Every value is substituted into a double-quoted YAML string by sed with | as its delimiter, so
# those characters (and the backslash and newline that would escape them) are refused rather than
# escaped. None of them belongs in an email address or a host:port.
check_value() {
  name=$1
  value=$2
  case "$value" in
    *'"'*|*'|'*|*'&'*|*'\'*) fail "$name contains a character that is not allowed (\" | & \\)" ;;
  esac
  if [ "$(printf '%s' "$value" | wc -l)" -ne 0 ]; then
    fail "$name contains a line break"
  fi
}

[ -n "${ALERT_EMAIL_TO:-}" ] || fail "ALERT_EMAIL_TO is not set; refusing to start an Alertmanager that cannot deliver"
[ -n "${ALERT_EMAIL_FROM:-}" ] || fail "ALERT_EMAIL_FROM is not set"
[ -n "${ALERT_SMTP_SMARTHOST:-}" ] || fail "ALERT_SMTP_SMARTHOST is not set"

ALERT_SMTP_USERNAME=${ALERT_SMTP_USERNAME:-}
ALERT_SMTP_PASSWORD=${ALERT_SMTP_PASSWORD:-}
ALERT_SMTP_REQUIRE_TLS=${ALERT_SMTP_REQUIRE_TLS:-true}

# A username with no password would fail authentication on every send. The reverse is the local
# stack's case: its mail catcher takes no credentials at all.
if [ -n "$ALERT_SMTP_USERNAME" ] && [ -z "$ALERT_SMTP_PASSWORD" ]; then
  fail "ALERT_SMTP_USERNAME is set but ALERT_SMTP_PASSWORD is not"
fi

case "$ALERT_SMTP_REQUIRE_TLS" in
  true|false) ;;
  *) fail "ALERT_SMTP_REQUIRE_TLS must be true or false" ;;
esac

check_value ALERT_EMAIL_TO "$ALERT_EMAIL_TO"
check_value ALERT_EMAIL_FROM "$ALERT_EMAIL_FROM"
check_value ALERT_SMTP_SMARTHOST "$ALERT_SMTP_SMARTHOST"
check_value ALERT_SMTP_USERNAME "$ALERT_SMTP_USERNAME"

umask 077
printf '%s' "$ALERT_SMTP_PASSWORD" > /etc/alertmanager/smtp-password

sed -e "s|__ALERT_EMAIL_TO__|${ALERT_EMAIL_TO}|" \
    -e "s|__ALERT_EMAIL_FROM__|${ALERT_EMAIL_FROM}|" \
    -e "s|__ALERT_SMTP_SMARTHOST__|${ALERT_SMTP_SMARTHOST}|" \
    -e "s|__ALERT_SMTP_USERNAME__|${ALERT_SMTP_USERNAME}|" \
    -e "s|__ALERT_SMTP_REQUIRE_TLS__|${ALERT_SMTP_REQUIRE_TLS}|" \
    "$TEMPLATE" > "$RENDERED"

if grep -q "__ALERT_" "$RENDERED"; then
  fail "a placeholder in alertmanager.yml was left unfilled"
fi

# Check the rendered file too, not only the template: a bad address fails here, at startup, rather
# than at the first alert.
/bin/amtool check-config "$RENDERED" > /dev/null || fail "the rendered config does not validate"

exec /bin/alertmanager "$@"
