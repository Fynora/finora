#!/bin/sh
# Refuses to start Grafana without a real admin password, then hands over to the stock entrypoint.
#
# WHY THIS EXISTS
# ---------------
# Grafana's own default is admin/admin, and it starts perfectly happily with it. On a service that
# may carry a public Railway domain, that is an administrator account on the internet with a
# password printed in every Grafana tutorial ever written. Nothing in Grafana warns about it beyond
# a change-password prompt that a logged-in attacker simply declines.
#
# The failure this prevents is not "someone chose a weak password" -- it is "nobody set one at all",
# which is a single forgotten environment variable on a service created in a hurry. That is exactly
# the shape of the mistakes this repository has actually made before, so it is checked rather than
# documented.
#
# It also refuses the local validation stack's password. ops/monitoring/docker-compose.yml uses
# admin/admin deliberately -- it is a throwaway stack holding nothing -- and copying those values
# into Railway variables is the obvious, wrong shortcut.
set -eu

fail() {
    echo "FATAL: $1" >&2
    echo >&2
    echo "Set GF_SECURITY_ADMIN_PASSWORD on this Railway service to a long random value." >&2
    echo "See ops/monitoring/README.md, 'Deploying this to production'." >&2
    exit 1
}

if [ -z "${GF_SECURITY_ADMIN_PASSWORD:-}" ]; then
    fail "GF_SECURITY_ADMIN_PASSWORD is not set, so Grafana would start with its default password."
fi

case "${GF_SECURITY_ADMIN_PASSWORD}" in
    admin|password|grafana|changeme)
        fail "GF_SECURITY_ADMIN_PASSWORD is a well-known default value."
        ;;
esac

# 16 rather than 8: this is a single shared admin account with no rate limiting in front of it and
# no second factor, not a per-user password backed by a lockout policy.
if [ "${#GF_SECURITY_ADMIN_PASSWORD}" -lt 16 ]; then
    fail "GF_SECURITY_ADMIN_PASSWORD is shorter than 16 characters."
fi

# Never echo the value, not even a fingerprint -- container logs are a place secrets leak from, and
# a length is enough to confirm the variable arrived.
echo "Admin password present (${#GF_SECURITY_ADMIN_PASSWORD} characters). Starting Grafana."

exec /run.sh "$@"
