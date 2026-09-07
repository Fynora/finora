# OpenAPI-generated contracts

Status: **Phase 0-2 shipped** (2026-09). One enum (`Transaction.ReconciliationStatus`) migrated
end-to-end across backend, frontend, mobile, and admin-portal, plus an advisory (non-blocking) CI
drift check. The remaining ~166 hand-written types across the three clients are **not** migrated
yet — that's the deliberately-deferred Phase 3, scoped separately once this phase has been lived
with for a while. See the architecture-audit and migration-estimate artifacts from this project's
history for the full phase breakdown and why a narrower first slice was chosen over migrating
everything at once.

## Why this exists

Each of `frontend`, `mobile`, and `admin-portal` hand-maintains its own copy of the backend's
DTOs and enums in a local `src/types/index.ts`. That already drifted for real: the backend's
`Transaction.ReconciliationStatus` enum has 7 values, but mobile's hand-written copy only had 4
(missing `REVERSAL`, `INVESTMENT_TRANSFER`, `SUPERSEDED`), and admin-portal carried two different
partial copies in two different files — one of them not even a union type, just `string`. None of
this was caught by a build, a lint rule, or a test, because nothing compared the hand-written
copies against the backend.

## Architecture

```
backend (Spring Boot, springdoc-openapi)
        │
        │  backend/scripts/generate-openapi-spec.sh
        ▼
backend/openapi/openapi.json          ← committed, reviewed like any other generated artifact
        │
        │  openapi-typescript, run per client via `npm run generate:types`
        ├──▶ frontend/src/api/generated-types.ts
        ├──▶ mobile/src/api/generated-types.ts
        └──▶ admin-portal/src/api/generated-types.ts
                │
                │  a hand-written type in each client's types/index.ts imports and re-exports
                │  ONE derived type alias, rather than the client being rewritten wholesale
                ▼
        export type ReconciliationStatus =
          NonNullable<components['schemas']['TransactionDto']['reconciliationStatus']>;
```

**Types only, not a generated HTTP client.** `openapi-typescript` produces TypeScript types with
no runtime code. Each client's existing hand-written `api/client.ts` / `api/endpoints.ts` axios
layer is untouched — it still makes the same requests, with the same retry/offline/error-handling
logic (mobile's PDF-password retry flow, the `ENTITLEMENT_REQUIRED`/`AUTH_ACCOUNT_DEACTIVATED`
interceptor branches, etc.). A full generated client was considered and rejected: it would have
had to reimplement all of that platform-specific behavior to be safe to adopt, which is
substantially riskier than swapping type annotations.

**Surgical extraction, not a wholesale type-file replacement.** `generated-types.ts` marks nearly
every field optional (`?`) by default, because the backend's DTOs are Java records without
`@NotNull`/`required` annotations springdoc could use to mark them non-optional in the schema.
Swapping a whole hand-written interface for the generated one would have silently made every field
optional throughout that interface — a real regression. Phase 2 instead extracts one derived type
alias per migrated field (see the `ReconciliationStatus` example above) and keeps the surrounding
hand-written interface's own field, which stays non-optional. Phase 3 will need to design around
this properly (e.g. deciding whether to start annotating backend DTOs with validation constraints
so springdoc marks fields required) rather than accepting the optionality regression at scale.

## What Phase 2 actually changed on the backend

Generating a schema only reflects what the backend's Java types already say. `TransactionDto`
declared `reconciliationStatus` as a plain `String`, populated via `.name()` on the entity's real
enum — a normal-looking DTO-mapping pattern that happens to erase the enum's value set before
springdoc ever sees it. **This means "generate types" alone would not have fixed the confirmed
drift bug** — it would have produced `reconciliationStatus: string`, no better than admin-portal's
existing untyped copy. Phase 2 also changed
[`TransactionDto.java`](../../backend/src/main/java/com/finora/transactions/TransactionDto.java)
to declare the field as `Transaction.ReconciliationStatus` directly, dropping the `.name()` call.
Wire format is unchanged (Jackson serializes an enum as its name by default, identical to what
`.name()` produced), verified by rerunning the full backend suite (4,149 tests) after the change.

This same `.name()`-widening pattern exists on other fields in the same DTO (`type`,
`counterpartyType`) and is very likely repeated across other DTOs — **Phase 3's actual first
step should be auditing how many other fields need the same treatment**, not assumed to be
`ReconciliationStatus`-only. This wasn't fully enumerated in Phase 2 by design (out of the
approved scope), so treat that count as unknown, not small.

## Developer workflow

Regenerating the spec needs a reachable Postgres matching `docker-compose.yml`'s credentials
(`finora`/`finora`/`finora` on `5432`) — start it however you normally run the backend locally.

```bash
cd backend
./mvnw -DskipTests package
DB_HOST=localhost DB_PORT=5432 DB_NAME=finora DB_USER=finora DB_PASSWORD=finora \
  ./scripts/generate-openapi-spec.sh
```

This boots the jar under `SPRING_PROFILES_ACTIVE=dev` on a scratch port (`8098` by default,
override with `OPENAPI_GEN_PORT`), curls `/v3/api-docs`, writes the pretty-printed result to
`backend/openapi/openapi.json`, and shuts the jar down. It fails loudly (non-zero exit) if the
endpoint doesn't come up healthy or returns anything but 2xx — see "Why a script, not a Maven
plugin" below for why that specific failure mode matters here.

Then, in whichever client(s) changed:

```bash
cd frontend && npm run generate:types   # or mobile/, or admin-portal/
```

Each client's `generate:types` script runs `npx --yes openapi-typescript@7.13.0` directly against
`../backend/openapi/openapi.json` — **deliberately not installed as a devDependency**. It was
tried first; installing it in `mobile/` with `--legacy-peer-deps` (needed because
`openapi-typescript`'s peer range is `typescript@^5.x` and mobile pins `~6.0.3`) caused npm to
re-resolve enough of the tree that `@react-native/jest-preset` dropped out and the whole mobile
test suite failed to boot. The peer-range mismatch itself is harmless — running the generator
against mobile's real TS 6.0.3 install was verified to work fine — but getting there via a full
dependency reinstall was not worth the risk to a working lockfile. `npx` with a pinned version
gets the same deterministic output without touching any client's `package-lock.json`.

Both the spec and each client's `generated-types.ts` are committed — review the diff like any
other generated artifact (it's large and mostly noise on a real backend change, but the specific
lines that changed are the point).

## CI workflow

`.github/workflows/ci.yml`'s `openapi-contract-check` job runs on every PR and push to `main`. It
rebuilds the backend, regenerates the spec and all three clients' types the same way a developer
would locally, and diffs each against what's committed. **It is advisory, not blocking** — a
mismatch posts a `::warning::` annotation on the run and the job stays green either way. This was
a deliberate, explicit scope decision (not an oversight): the workflow needs to prove itself
noise-free for a release cycle before flipping to a hard failure is worth proposing.

## Why a script, not `springdoc-openapi-maven-plugin`

The obvious alternative to `backend/scripts/generate-openapi-spec.sh` is springdoc's own Maven
plugin, generating the spec as part of the build with no separate boot-and-curl step. It was
rejected specifically because this repository already has a real incident with springdoc itself:
the version pinned in `backend/pom.xml` carries a comment describing a Spring Boot minor bump that
made `springdoc-openapi-starter-webmvc-ui` 2.6.0 return a 500 on every single request to
`/v3/api-docs`, silently, caught only by someone manually curling the running app. A dedicated
codegen plugin is one more separately-versioned dependency that can break the exact same way on
the next Spring Boot bump. The script instead reuses the boot-and-curl pattern
`.github/workflows/ci.yml`'s `smoke` job already uses for a different purpose, and its `curl -f`
fails the whole step immediately if the endpoint isn't healthy or doesn't return 2xx — the direct
guard against that specific historical failure re-occurring silently.

## Schema fidelity — what was checked before migrating anything

Before Phase 2 touched any code, the backend was audited for patterns that make a generated
OpenAPI schema diverge from what the API actually returns at runtime:

| Pattern | Found? | Detail |
|---|---|---|
| `@JsonIgnore` | No | Zero occurrences in `dto/` or `entity/`. |
| `@JsonView` | No | Zero occurrences anywhere in the backend. |
| Custom Jackson serializers/deserializers | No | Zero occurrences. |
| `@JsonValue` (custom enum wire representation) | No | Zero occurrences — every enum serializes as its plain `.name()`. |
| Raw entities returned from a controller | No | Every controller method returns a DTO/record; JPA entities never reach a response body directly. |
| `Map<String, Object>` / `Map<String, T>` fields | Yes, ~10 DTOs | Legitimate: genuinely dynamic data (audit metadata, admin diagnostics payloads). Springdoc correctly renders these as an untyped `object` — that's an accurate schema, not a gap, since the data itself has no fixed shape. |
| Generic wrapper types (`ApiResponse<T>`, `PagedResponse<T>`) | Yes | Plain Java records, no inheritance or polymorphism. springdoc 2.9.0 resolves each concrete usage to its own named schema (e.g. `ApiResponseTransactionDto`) correctly — verified directly against the generated spec, not assumed. |
| Sealed interfaces / polymorphic DTOs | No | None exist in the codebase — the hardest class of OpenAPI schema gap doesn't apply here. |
| Binary/file responses (`ResponseEntity<byte[]>`, etc.) | Yes, 3 controllers | `AdminHeldStatementController`, `StatementImportController`, `AdminHeldImportController` — statement/PDF downloads. Correctly out of scope for type generation; these were never going to be typed beyond their content-type. |
| Enum fields widened to `String` before the DTO (the actual gap that mattered) | Yes | See "What Phase 2 actually changed on the backend" above — this was the real, non-hypothetical finding, not any of the Jackson-annotation patterns initially suspected. |

Determinism was also verified directly, not assumed -- and it wasn't true on the first attempt.
springdoc stamps `servers[0].url` with whatever `host:port` the generating request came in on, so
the first version of this script left the committed spec dependent on which scratch port happened
to generate it: regenerating on a different `OPENAPI_GEN_PORT` produced a real diff with zero API
change behind it. The script now drops `servers` before writing the file (openapi-typescript never
reads it, so nothing downstream is affected -- confirmed by regenerating all three clients' types
against the stripped spec and getting a zero diff). With that fixed, determinism holds two ways
that were both checked directly: regenerating on two different ports (`8098` and `9111`) on the
same machine produced byte-identical output, and `ci.yml`'s `openapi-contract-check` job -- a
different OS, JVM, and Python than whatever generated the committed file -- reports the spec and
all three clients' generated types as up to date on every run rather than flagging permanent drift.

## Troubleshooting

**`generate-openapi-spec.sh` exits with "Backend never became healthy"** — usually a DB connection
problem. Confirm Postgres is reachable at the `DB_HOST`/`DB_PORT` you passed, with the
`finora`/`finora`/`finora` database/user/password `docker-compose.yml` expects (the script doesn't
start Postgres itself, to stay consistent with the rest of this repo's backend-boot tooling — see
the script's own header comment).

**`generate-openapi-spec.sh` exits with "Generated spec looks empty"** — the endpoint returned
200 but with zero paths or schemas, which almost always means a fresh/empty database that
Flyway hasn't migrated yet, or `springdoc.api-docs.enabled: false` is somehow active (it's `dev`
profile-only by design — see `application-prod.yml`'s comment on why it's off in production).

**A client's `npm run generate:types` fails to resolve `openapi-typescript`** — it isn't a
dependency; the script fetches it via `npx --yes openapi-typescript@7.13.0` on demand, so it
needs npm registry access. There is intentionally no offline/vendored fallback yet.

**CI's `openapi-contract-check` posts a warning** — it means a backend DTO/enum change went in
without a matching `npm run generate:types` in one or more clients. Regenerate locally per the
developer workflow above, review the diff, and commit it. The job will not block your merge on
its own, but a persistently-stale generated file defeats the entire point of this migration.
