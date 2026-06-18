# Regenerating the OpenAPI Snapshot

This document explains the two OpenAPI artifacts the Honcho Inspector
backend ships, when each one needs to be updated, and how a drift check
ties them together in CI. The intent is to keep a single source of truth
for both human readers (developers, operators, integrators) and machine
consumers (client code generators, contract tests, Swagger UI).

The hand-written artifact lives at [openapi.yaml](openapi.yaml); the
generated artifact lives at
[openapi.generated.json](openapi.generated.json). The drift check that
keeps them aligned is
[OpenApiDriftCheckTest.java](../src/test/java/com/revytechinc/honchoinspector/docs/OpenApiDriftCheckTest.java).
For Honcho provider-layer concerns (how to add a new endpoint, how to
plug in a new upstream version) see
[honcho-providers.md](honcho-providers.md); this document stays focused
on the snapshot/contract-regeneration workflow and does not cover
provider design.

## Why two OpenAPI artifacts?

The backend publishes two OpenAPI 3.x documents that represent
overlapping views of the same API surface. They serve different
audiences, so they cannot collapse into a single file without losing
something each audience needs.

The hand-written
[docs/openapi.yaml](openapi.yaml) is the **narrative** source of truth.
It carries the workflow context: which `summary` and `description` to
show in Swagger UI, the recommended end-to-end flow (the
"workflow step 1 of N" notes sprinkled through the auth + profile
sections), worked examples for clients, and the human-friendly
`x-workflow-narrative` top-level extension that the springdoc toolchain
does not emit. It is the file that client code generators read, that
onboarding contributors read, and that the README points at. The
hand-written YAML is intentionally richer than the live runtime spec
because the audiences reading it need prose, not just shape.

The springdoc-generated
[docs/openapi.generated.json](openapi.generated.json) is the **live
contract** source of truth. Springdoc introspects the running Spring
MVC controllers at request time, walks every `@Operation`,
`@ApiResponse`, `@Parameter`, and `@Schema` annotation on the DTOs and
endpoints, and serializes the result as an OpenAPI 3.0.x document. It
is what the app actually emits at `/v3/api-docs` while it runs, so
anything in this file is by definition reachable. If a path or method
appears here, you can `curl` it; if it does not appear here, the
controller does not actually serve it.

The drift check, implemented in
[OpenApiDriftCheckTest](../src/test/java/com/revytechinc/honchoinspector/docs/OpenApiDriftCheckTest.java),
enforces that the two views stay aligned on the structural shape that
matters: which paths exist, which HTTP methods each path accepts, and
which `operationId` each (path, method) pair carries. It deliberately
ignores `description`, `example`, `parameters` detail, `requestBody`
detail, and `responses` detail, because the hand-written YAML is
allowed to be richer than what springdoc produces from annotations.
The test fails CI if either side drifts in a way the other does not
match, which keeps the spec honest in both directions.

## When to regenerate the snapshot

The snapshot at
[docs/openapi.generated.json](openapi.generated.json) is a stale-by-construction
artifact: it captures what the app looked like the last time someone ran
the regeneration command. It does not auto-update on controller edits,
so the answer to "do I need to regenerate?" is mechanical: did the
runtime shape of the API change?

Regenerate after adding a new controller endpoint. A new
`@GetMapping`, `@PostMapping`, etc. on any `@RestController` will
produce a new path + method + `operationId` entry in the springdoc
output, and the drift check will fail the build until the snapshot is
refreshed and committed.

Regenerate after removing a controller endpoint. The same drift check
will fail in the opposite direction: the generated JSON will lose the
path, but the hand-written YAML will still list it. The fix is the
same: regenerate, commit the updated snapshot, and (separately) remove
the path from the hand-written YAML.

Regenerate after changing a DTO field name, type, or set of fields.
The `components.schemas` block in the generated JSON mirrors the
`@Schema` annotations on the DTO records, so adding a field, renaming
one, or changing a type will change the shape springdoc emits.
Renaming a field on a record usually also breaks the JSON wire
contract, so this kind of edit is intentionally a breaking change and
the regenerated snapshot is the canonical evidence of the new shape.

Regenerate after upgrading the springdoc dependency in `pom.xml`.
Springdoc 2.6.x renders annotations slightly differently from 2.5.x,
and the `openapi` field of the generated document moves between
3.0.1, 3.0.2, 3.0.3 across releases. The drift check tolerates the
3.0.1 vs 3.0.3 difference, but it does not tolerate wholesale
re-rendering changes; a springdoc upgrade is a legitimate reason to
regenerate.

Do NOT regenerate for documentation-only changes. Editing `summary`,
`description`, or `example` on a `@Operation` or `@Schema`
annotation only changes the in-process Swagger UI output for that
single server start. It does not change any structural shape that the
drift check cares about, and it does not need a snapshot commit. The
existing snapshot is still accurate; only a deployment that picks up
the new code will render the new prose.

Do NOT regenerate for changes that only touch the hand-written YAML.
The hand-written YAML is the input the drift check reads on the left
side, and the snapshot is the input it reads on the right side. A
left-side edit by itself will not be reflected in the snapshot, and
that is fine: the next time the snapshot is regenerated, the right
side will pick up any matching changes from the controllers. If the
right side does not change, the drift check will pass.

## How to regenerate

The regeneration command runs the `openapi-snapshot` Maven profile
defined in `pom.xml`. That profile boots the Spring Boot application
in `pre-integration-test`, curls `/v3/api-docs` in `integration-test`,
and stops the app in `post-integration-test`, writing the response
body to a gitignored `target/openapi-snapshot.json`. The `verify`
phase is the natural entry point because it runs the pre/integration
hooks even when no integration tests are present.

```bash
mvn -Popenapi-snapshot verify -DskipTests
```

The `-DskipTests` flag is important: without it, the build also runs
`OpenApiDriftCheckTest` against the *previous* snapshot before the
profile has produced a new one, which can produce a confusing failure
if the previous snapshot is already stale. Skipping tests for this
command keeps the focus on producing a fresh snapshot.

The output lands at `target/openapi-snapshot.json`, which is gitignored
alongside the rest of the `target/` directory. To commit the new
snapshot, copy it into place:

```bash
cp target/openapi-snapshot.json docs/openapi.generated.json
```

After copying, sanity-check that the new file is well-formed and that
springdoc did not silently downgrade the OpenAPI version. The
generated file should always declare a 3.0.x version because that is
what springdoc 2.6.0 emits; if it drops to 3.0.0 or jumps to 3.1.0,
the snapshot was captured against a different springdoc version than
the one in `pom.xml`.

```bash
jq '.openapi' docs/openapi.generated.json
# expected: "3.0.1"
```

Finally, run the drift check against the new snapshot to confirm
both files agree on shape. The drift test runs as part of the default
`mvn test` execution because it carries the `@Tag("drift")` annotation
and is registered under the standard Surefire test discovery. Running
it explicitly is useful when the regeneration is the only change in a
commit and you want a tight feedback loop:

```bash
mvn -B test -Dtest=OpenApiDriftCheckTest
```

If port 8080 is occupied (for example by an Angular dev server or a
previously started `mvn spring-boot:run`), the `openapi-snapshot`
profile can be told to bind a different port via the
`-Dopenapi.port=NNNN` system property, which the `spring-boot:start`
goal threads into `-Dserver.port` for the embedded server. The curl
target picks the same property up, so the command stays
self-contained.

```bash
mvn -Popenapi-snapshot verify -DskipTests -Dopenapi.port=18080
cp target/openapi-snapshot.json docs/openapi.generated.json
```

The default port remains 8080 to match the rest of the docs and the
bundled `application.yml`. Only override the port when something else
is bound to 8080 on the dev machine.

## How to update the hand-written YAML

The hand-written [docs/openapi.yaml](openapi.yaml) is the file human
readers and client code generators consume. It needs to be kept in
sync with the controller surface, and the drift check enforces that
sync on the structural fields (paths, methods, operationIds, tags).
For everything else, the YAML is allowed to be richer than what
springdoc produces, and that richness is the point.

When adding a new endpoint, append a new path block to the `paths:`
section. Use the existing path blocks in [docs/openapi.yaml](openapi.yaml)
as templates: every new path needs a `tags` array (matching the tag
declared on the controller's `@Tag` annotation), a one-line `summary`,
a multi-line `description` that includes the workflow step number
("Workflow step N of M"), a stable `operationId` (matching the method
name on the controller, or the `name` attribute on `@Operation` if
one is set), `parameters` for any `@PathVariable` and `@RequestParam`,
a `requestBody` with a `$ref` to a `components.schemas` entry if the
endpoint accepts a body, and a `responses` block.

The `responses` block should cover every status code the controller
can return. The proxy endpoints typically need `200` (or `201` for
creates), `400` (missing `X-Honcho-Profile-Id` header), and `404`
(profile not found / not owned by the current user), each pointing at
`$ref: '#/components/schemas/ErrorResponse'` for the non-2xx cases and
either a typed schema or `type: object` for the success cases. The
auth endpoints additionally need `401` (no valid session / bad
credentials) and the registration endpoint needs `409` (username
already exists). Inline an `example` on the success response that
shows a realistic payload, because the Swagger UI uses that example
as the default value when a developer clicks "Try it out".

When changing a DTO, update `components.schemas` to mirror the new
field set. The drift check ignores schema detail, but client
generators do not — they read field names, types, and required-ness
directly. Keep the `description` on each `@Schema`-annotated field
honest: the prose is what shows up in the generated client SDK
documentation, and "TODO" or "field" descriptions are noticeable
debt.

When removing an endpoint, delete the path block from the YAML. The
hand-written file and the springdoc snapshot are checked for the
existence of the path on both sides, so leaving a stale block will
trip the drift check the next time the snapshot is regenerated.

The top-level structure of the YAML (info, servers, tags, paths,
components) follows the OpenAPI 3.0.3 spec. The `x-workflow-narrative`
top-level extension is custom and is preserved across regenerations
because the drift check ignores extensions on the right-hand side;
if a future maintainer wants to evolve the narrative structure, the
extension is the right place.

## How drift check works

The drift check is a single JUnit 5 test class,
[OpenApiDriftCheckTest](../src/test/java/com/revytechinc/honchoinspector/docs/OpenApiDriftCheckTest.java),
annotated at the class level with `@Tag("drift")`. The tag matters:
it is the hook that makes the test skippable on the command line
without removing the assertion, and it is the hook CI uses to gate
the check behind a label rather than a build phase. The class lives
in the `docs` test package because it documents a contract, not a
piece of runtime behavior.

The test loads both
[docs/openapi.yaml](openapi.yaml) and
[docs/openapi.generated.json](openapi.generated.json) once in
`@BeforeAll` and walks each path's HTTP method map. The hand-written
file is parsed with SnakeYAML; the generated file is parsed with
Jackson. Both are reduced to the same
`Map<path, Map<method, Map<field, value>>>` shape so the comparison
logic does not need to know which side came from which parser.

The comparison checks three things and three things only. First, the
set of paths on the left must equal the set of paths on the right
(missing-from-generated, missing-from-handwritten). Second, for each
shared path, the set of HTTP methods on the left must equal the set
of HTTP methods on the right (same two failure directions, scoped to
the path). Third, for each (path, method) pair that exists on both
sides, the `operationId` strings must match exactly, including the
empty-string-versus-null distinction. On failure, the error message
is a multi-line dump naming each drift individually, prefixed with
`"Drift: "` and a one-line description of which side is missing
what: "Drift: hand-written has path /api/foo but generated doesn't",
"Drift: generated /api/foo has method PUT but hand-written doesn't",
"Drift: /api/foo GET operationId mismatch: hand-written='getFoo',
generated='fetchFoo'".

The test deliberately ignores everything else. `description`,
`example`, `parameters` detail, `requestBody` detail, `responses`
detail, `info` block, `servers` block, and `components.schemas` are
all out of scope, because the hand-written YAML is allowed to be
richer than what springdoc produces from annotations. The `openapi`
field at the top of both documents is also ignored, because
springdoc 2.6.0 emits `3.0.1` and the hand-written YAML declares
`3.0.3`; asserting on that field would make the drift check fail
permanently without any real change in the spec.

Operations marked `x-phase: "2"` in the hand-written YAML are stripped
from the left side before comparison, because they document endpoints
that have not been implemented yet. Springdoc will never produce
entries for them, so including them in the left side would cause a
permanent drift. The marker is checked on each operation node
(handles both the string `"2"` and the integer `2`, for YAML
coercion safety) and is the only extension the drift check reads.

The test runs by default during `mvn test` because Surefire picks up
every test class on the classpath, including the one with
`@Tag("drift")`. The tag is informational unless a profile or a
`-Dgroups` flag explicitly opts in or out. To skip the drift check
on a specific run (for example, while iterating on a YAML edit before
the snapshot is regenerated), use the JUnit 5 tag exclusion syntax:

```bash
mvn -B test -Dgroups=!drift
```

This keeps the rest of the test suite running and just excludes the
drift assertion. CI should never use this flag; local development can.

## Phase 2 path placeholders

The hand-written [docs/openapi.yaml](openapi.yaml) carries path
entries for endpoints that have not been implemented yet, under the
`x-phase: "2"` marker. Springdoc does not see them, because there is
no controller method to introspect, and the drift check ignores them
on the left side via the same marker. The net effect is that the
hand-written file can document Phase 2 endpoints as "coming soon"
without the drift check ever failing because of them.

The Phase 2 path set is currently scoped to four prefixes:
`/api/orgs/**`, `/api/stats/**`, `/api/reports/**`, and
`/api/invites/**`. These cover the multi-tenant org model, the
cross-workspace usage stats, the periodic PDF / CSV reports, and the
user-invite flow. Each path block carries a `x-phase: "2"` extension
on the operation node (one entry per HTTP method), and a description
in the form of "Phase 2 placeholder: ..." so a reader hitting
Swagger UI understands why the path is documented but unreachable.

To add a new Phase 2 placeholder, append a new path block to
[docs/openapi.yaml](openapi.yaml) with the `x-phase: "2"` marker on
the operation node. The drift check will see the marker and skip the
path, so no snapshot regeneration is required for placeholder
additions. Once the underlying controller is implemented, remove the
`x-phase: "2"` marker and regenerate the snapshot, and the new path
will start showing up in the springdoc output and the drift check
will assert on it normally.

To promote a Phase 2 placeholder to a Phase 1 live path, do the
reverse: implement the controller, delete the `x-phase: "2"`
extension, regenerate the snapshot, and commit. The drift check is
the gate: a "promoted but marker not removed" file will pass locally
(the marker is stripped before comparison) but will leave dead text
in the YAML, so the marker is best removed in the same commit as
the snapshot refresh.

## CI integration

The drift check runs as part of every `mvn verify` invocation because
`OpenApiDriftCheckTest` is registered under the default Surefire test
discovery and carries no skip annotation. The CI workflow at
[.github/workflows/ci.yml](../../.github/workflows/ci.yml) runs
`mvn -B verify` on every push and every pull request, so a drift
between [docs/openapi.yaml](openapi.yaml) and
[docs/openapi.generated.json](openapi.generated.json) fails the build
the same way a unit-test failure would.

The CI workflow does not run the regeneration profile on its own.
This is intentional: regenerating a snapshot inside CI would mask a
real drift (CI would silently fix the discrepancy instead of telling
the contributor to fix their YAML or commit a fresh snapshot). The
contract is: CI verifies, humans regenerate. The contributor who
introduces the drift is the same person who can read the failure
message and fix it locally, and they do so by either aligning the
YAML with the controllers or regenerating the snapshot via
`mvn -Popenapi-snapshot verify -DskipTests` and committing the
updated [docs/openapi.generated.json](openapi.generated.json).

To regenerate the snapshot in CI (for example, in a follow-up commit
that fixes the drift), do not edit
[docs/openapi.generated.json](openapi.generated.json) by hand. The
file is generated, and hand-edits to it are exactly the kind of drift
the check exists to catch. Instead, run the regeneration command
locally, review the diff for unexpected changes, and commit the
result. The CI run on the resulting commit should pass.

The full pipeline for a typical drift-fixing commit is:

```bash
# 1. Make the code change (controller, DTO, etc.)
# 2. Update docs/openapi.yaml to match (see the previous section)
# 3. Regenerate the snapshot
mvn -Popenapi-snapshot verify -DskipTests
cp target/openapi-snapshot.json docs/openapi.generated.json
# 4. Run the drift check locally to confirm CI will pass
mvn -B test -Dtest=OpenApiDriftCheckTest
# 5. Commit the code change, the YAML update, and the snapshot together
```

The three artifacts (code, hand-written YAML, generated snapshot)
should move together; a commit that touches only one of them is the
fastest way to produce a CI failure on the next push.

## Related

- [docs/openapi.yaml](openapi.yaml) — hand-written contract (T19)
- [docs/openapi.generated.json](openapi.generated.json) — springdoc snapshot (T20)
- [src/test/java/com/revytechinc/honchoinspector/docs/OpenApiDriftCheckTest.java](../src/test/java/com/revytechinc/honchoinspector/docs/OpenApiDriftCheckTest.java) — drift check (T21)
- `pom.xml` — `openapi-snapshot` Maven profile that drives regeneration
- [.github/workflows/ci.yml](../../.github/workflows/ci.yml) — CI workflow that runs the drift check
- [docs/honcho-providers.md](honcho-providers.md) — provider-layer anatomy and add-endpoint tutorial
- [docs/regenerating-fixtures.md](regenerating-fixtures.md) — sibling doc for the Honcho v3 fixture regeneration flow
