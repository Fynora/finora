#!/bin/sh
# Makes the mounted volume writable, then runs Prometheus as an unprivileged user.
#
# WHY THIS EXISTS
# ---------------
# Railway mounts a volume owned by root. The prom/prometheus image runs as `nobody`, so the first
# thing Prometheus does with its storage directory -- open /prometheus/queries.active -- fails with
# "permission denied", and that error is fatal: the process exits immediately.
#
# What makes this worth a script rather than a note in the README is how it presents. Railway
# reports the DEPLOYMENT as successful, because the container did start. The service shows Online.
# Only the container's own log says Prometheus died, and only if you know to look past the build
# log. Diagnosing it from the outside looks like "the volume broke the deploy", which is exactly
# the wrong conclusion -- it cost an hour of detaching and reattaching the volume to find this line.
#
# WHY NOT JUST RUN AS ROOT
# ------------------------
# `USER root` with no drop would also work and is what most Railway/Prometheus examples do. Only
# the chown needs privilege, though, so the long-running process -- the one reachable from other
# services on the private network -- does not have to be the one running as root.
#
# chpst, not setpriv. The image's busybox does ship setpriv, but only the capability half of it:
# --reuid/--regid are not compiled in and it exits with a usage message. Found by running it.
set -eu

# -R rather than a plain chown: a volume reattached after a previous run has a tsdb tree underneath
# it, and only fixing the top directory leaves Prometheus unable to read its own history.
chown -R nobody:nobody /prometheus

exec chpst -u nobody:nobody /bin/prometheus "$@"
