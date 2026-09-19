#!/bin/sh
# Runs INSIDE the backend container image, not on the CI host -- ci.yml's backend-image job mounts
# it read-only and runs it as the container's entrypoint (its shebang selects the image's own
# shell, so it needs the executable bit set in git):
#
#   docker run --rm --entrypoint /check.sh \
#       -v "$PWD/scripts/check-backend-image.sh:/check.sh:ro" finora-backend:ci
#
# It checks the properties backend/Dockerfile promises in its comments, so a future edit that
# quietly breaks one of them fails in CI instead of in production (audit finding F-10):
#
#   * non-root: the runtime image must not run as root (see the Dockerfile's USER comment).
#   * tesseract + eng data: TesseractRecogniser shells out to it, and `apk add` succeeding does not
#     prove the language data package landed where the engine looks for it.
#   * java: the JRE the pinned base image is supposed to provide actually runs.
#   * entrypoint and app.jar: what ENTRYPOINT executes is really there and executable.
#
# Overriding --entrypoint matters: the image's own ENTRYPOINT starts the application, which needs
# a database and secrets. These checks only need the filesystem and the installed binaries.
set -eu

uid="$(id -u)"
if [ "$uid" = "0" ]; then
  echo "::error::backend image runs as root (uid 0); the Dockerfile's USER instruction is not taking effect"
  exit 1
fi
echo "runs as uid $uid (non-root)"

tesseract --version 2>&1 | head -1
if ! tesseract --list-langs 2>&1 | grep -qx eng; then
  echo "::error::tesseract has no 'eng' language data; TesseractRecogniser would fail at runtime"
  exit 1
fi
echo "tesseract has the eng language data"

java -version 2>&1 | head -1

if [ ! -x /app/docker-entrypoint.sh ]; then
  echo "::error::/app/docker-entrypoint.sh is missing or not executable"
  exit 1
fi
if [ ! -f /app/app.jar ]; then
  echo "::error::/app/app.jar is missing"
  exit 1
fi
echo "entrypoint and app.jar present"
