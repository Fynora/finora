# Multi-stage build: compile with Maven, run on a slim JRE.
#
# Both base images are pinned by DIGEST as well as tag (audit finding F-10, 2026-09-19). A bare tag
# such as `eclipse-temurin:25-jre-alpine` is a moving pointer, resolved whenever the image is
# built. Railway builds from this file (see railway.json, `builder: DOCKERFILE`), so a rebuild
# could pick up a different base image -- a new Alpine release, a new JRE build, or, in the worst
# case, a tampered tag -- with no change to this repository at all. With the digest, a rebuild of
# the same commit starts from the same base image; the base only changes when this file does.
#
# The tag stays in the reference on purpose: it is what a human reads, and it is what Dependabot's
# `docker` ecosystem (.github/dependabot.yml, /backend) matches to propose a newer tag AND its new
# digest together, as one reviewable PR. The digest is the multi-arch index digest, not one
# platform's manifest, so this builds the same on amd64 and arm64.
#
# What this does NOT pin: `apk add` below still resolves package versions at build time, from the
# package index of whichever Alpine release the pinned base is. Pinning exact apk versions was
# considered and rejected -- Alpine removes superseded package versions from its repositories, so
# an exact pin turns a routine rebuild into a hard failure with no security gain over the digest
# pin plus the nightly image scan (.github/workflows/image-scan-nightly.yml), which is what actually
# reports a vulnerable package.
FROM maven:3-eclipse-temurin-24@sha256:a137a467ec89b5713d0be817b55bdba6b4d6ef16e3d05565a79bc08d8e775a1c AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

FROM eclipse-temurin:25-jre-alpine@sha256:2ca9adf44f5c29d28ecd26cf92d75cc0c66b7f32bfd839a4439e363a8b428af8
WORKDIR /app

# The engine behind TesseractRecogniser (com.finora.imports.pdf.ocr) -- an operational dependency
# of THIS deployment, not a library the build pulls in. See
# docs/engineering/import/ocr-engine-evaluation.md for the evidence that justified deploying it:
# measured through the real parser, not just for character accuracy. -data-eng is separate from
# the engine package because Alpine ships trained-data packages per language rather than bundling
# English into the base install.
RUN apk add --no-cache tesseract-ocr tesseract-ocr-data-eng

# Which commit this image was built from, surfaced by Platform Diagnostics.
#
# git-commit-id-maven-plugin cannot supply it here: it reads a .git directory at build time, and
# there is none to read. The build context is backend/ while .git sits at the repository root, so
# it is out of reach rather than merely uncopied -- and copying it in would mean moving the build
# context to the root, shipping the whole history in a layer, and busting the Docker cache on every
# commit. For one string, passing the string is proportionate.
#
# Declared in the RUNTIME stage on purpose. Put in the build stage it would invalidate the cached
# dependency and compile layers on every commit, turning a fast rebuild into a full one for a value
# nothing compiles against.
#
# Optional. Unset, the app falls back to RAILWAY_GIT_COMMIT_SHA, and reports unavailable if that
# is absent too -- see application.yml's app.build.commit.
ARG GIT_COMMIT=""
ENV GIT_COMMIT=${GIT_COMMIT}

# Railway exposes its own variables to a Dockerfile build as BUILD ARGUMENTS, which a build only
# receives if it declares them -- an undeclared ARG is simply not passed, silently. Declaring it
# here covers the case where the platform provides this at build time but not in the running
# container's environment, which is invisible from outside: the field just stays blank, exactly as
# it did when git-commit-id-maven-plugin had no .git to read.
#
# Baking it as ENV is safe even when the platform ALSO injects it at runtime -- a runtime
# environment variable takes precedence over an image ENV, so the live value wins and this is only
# ever the fallback. Defaulted empty so a plain `docker build` with no args still works.
ARG RAILWAY_GIT_COMMIT_SHA=""
ENV RAILWAY_GIT_COMMIT_SHA=${RAILWAY_GIT_COMMIT_SHA}

# Production-readiness pass: this image used to run as root (Alpine JRE images default to root
# if nothing says otherwise) — a container compromise via any dependency vulnerability then has
# root inside the container for free, no privilege escalation needed. A dedicated, unprivileged
# user costs nothing functionally (the app doesn't write anywhere in the container that isn't
# already writable) and is standard container-security hygiene for anything handling real user
# financial data.
RUN addgroup -S finora && adduser -S finora -G finora
COPY --from=build /app/target/finora-backend-*.jar app.jar
COPY docker-entrypoint.sh docker-entrypoint.sh
# -R (not just the two files above) because docker-entrypoint.sh's own GOOGLE_APPLICATION_CREDENTIALS_BASE64
# handling writes a new file into /app at container startup -- the unprivileged finora user needs
# write access to the directory itself for that, not just execute/read on the files that already
# existed at build time.
RUN chown -R finora:finora /app && chmod +x docker-entrypoint.sh
USER finora

# EXPOSE is documentation only -- it doesn't control which port the app actually binds to (that's
# server.port / $PORT, see application.yml's own comment) or affect Railway's own port detection,
# which reads $PORT directly. 8080 is simply the local/Docker default when $PORT is unset.
EXPOSE 8080

# -XX:MaxRAMPercentage (rather than a fixed -Xmx) lets the JVM heap scale with whatever container
# memory limit the deployment actually has (Railway plan size, a Docker --memory flag, etc.)
# instead of a number picked for one specific environment. 75% leaves headroom for
# non-heap JVM overhead (metaspace, thread stacks, direct buffers -- PDFBox/PdfPreviewGenerator's
# PDF parsing uses some of this) without wasting most of a small container's memory the way the
# JVM's own default (25%) would on a single-application container like this one.
#
# Shell-form entrypoint script now (not exec-form `java -jar` directly) -- see
# docker-entrypoint.sh's own doc comment: it needs to run BEFORE the JVM starts, to decode
# GOOGLE_APPLICATION_CREDENTIALS_BASE64 into a real file and export GOOGLE_APPLICATION_CREDENTIALS,
# since Railway (and most PaaS providers) have no other way to get an actual file into the container.
ENTRYPOINT ["./docker-entrypoint.sh"]
