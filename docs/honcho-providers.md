# Honcho Provider Layer

> **Audience:** operators, contributors, and integrators who need to add Honcho
> API coverage, plug in a custom upstream, or migrate to a new Honcho major
> version. Operators running an unmodified install do not need this document;
> see [`README.md`](../README.md) for end-user docs and [`docs/SECURITY.md`](SECURITY.md)
> for hardening.
>
> **Status:** Phase 1 (Wave 1+2) — V3 is fully implemented; V4 is a
> walkthrough-only scaffold at the time of writing. All "Adding V4 support"
> steps below describe a future task whose concrete artifacts do not yet
> exist in this repository.

The provider layer is the small, opinionated piece of code that turns a
generic Honcho-agnostic proxy into a versioned, per-resource, dispatcher.
It is intentionally narrow: one interface (`HonchoProvider`), one enum per
axis (`HonchoOperation` for "what", `HonchoApiVersion` for "which Honcho"),
one factory (`HonchoClientFactory`), one registry (`HonchoProviderRegistry`),
and eight `@Component` classes that do the actual work for Honcho v3.

This document explains the layer, walks through three real changes
("add a V3 endpoint", "override a default provider", "add V4 support"),
and records the four most common operator errors along with their fixes.

---

## Table of contents

1. [Why this layer exists](#1-why-this-layer-exists)
2. [Architecture overview](#2-architecture-overview)
3. [Anatomy of a provider](#3-anatomy-of-a-provider)
4. [Adding a new endpoint to V3](#4-adding-a-new-endpoint-to-v3)
5. [Adding a new endpoint without forking](#5-adding-a-new-endpoint-without-forking)
6. [Adding V4 support](#6-adding-v4-support)
7. [Troubleshooting](#7-troubleshooting)
8. [Strict mode](#8-strict-mode)
9. [Phase 2 forward-compat notes](#9-phase-2-forward-compat-notes)

---

## 1. Why this layer exists

Honcho is a moving target. The v2 to v3 transition (Honcho upstream
release `3.0.0`) changed six contract details that the legacy
`HonchoController` hard-coded — see `HonchoOperation.java` for the
authoritative list. The six operations that flipped from GET to POST
were the ones the v2 controller had been calling as reads:

| `HonchoOperation`         | v2 method | v3 method |
| ------------------------- | --------- | --------- |
| `LIST_PEERS`              | GET       | POST      |
| `LIST_SESSIONS`           | GET       | POST      |
| `SEARCH_PEERS`            | GET       | POST      |
| `QUERY_PEER_CONCLUSIONS`  | GET       | POST      |
| `SEARCH_MESSAGES`         | GET       | POST      |
| `SEARCH_SESSION_MESSAGES` | GET       | POST      |

The v2 controller was a single ~250-line file with every endpoint
inlined. Every time Honcho changed a path, a verb, or an envelope, the
controller needed editing in 24 places. The provider layer exists to
make that scaling problem go away:

- **Updatable.** The 24 endpoints are now 8 `HonchoProvider` classes
  (one per resource cluster), each of which knows its own paths, verbs,
  and bodies. A Honcho release that flips verbs only touches the one
  provider that owns the affected cluster — typically 10 to 30 lines of
  diff, not 24 cross-cutting edits.

- **Extensible.** A third party who needs a custom upstream (a
  self-hosted Honcho with bespoke auth, a stub for tests, a recording
  proxy for compliance) can drop a `@Component` that implements
  `HonchoProvider` and have it auto-discovered. The
  `HonchoProviderRegistry` resolves collisions deterministically
  (alphabetically-first class name wins) and logs a WARN whenever two
  providers claim the same `HonchoOperation`.

- **Versioned.** The same operator who needs to support a future v4
  upstream (different paths, different verbs, different body
  envelopes) can add a parallel `HonchoV4Client` and a set of
  V4-only providers. The `HonchoClientFactory` indexes every
  registered `HonchoClient` bean by the versions it claims, and the
  `HonchoProviderRegistry` filters providers to the version it was
  built for. Both fail loudly at boot if the configuration is
  inconsistent — there is no silent fall-through to the wrong version.

- **Replaceable.** Each `HonchoProvider` is a plain `@Component` with
  one method (`execute`) doing the actual upstream call. The shared
  helpers (`V3ProviderSupport` for the v3 package, plus the per-class
  `substitutePath` / `buildUri` / `applyAuth` pair in `PeersProviderV3`)
  are package-private and have no Spring magic, so a refactor that
  changes the HTTP transport (for example, swapping `RestClient` for
  the JDK 25 `HttpClient`) touches one file, not the whole layer.

- **Testable.** The provider surface is small enough that each
  provider has its own unit test class asserting path, method, body
  shape, and error translation. The `HonchoProviderRegistry` has its
  own unit tests covering the deterministic collision rule. The
  `HonchoClientFactory` has its own unit tests covering the
  version-collision boot-time check. There is no "integration test
  must pass before I can change a verb" tax.

The cost is one extra layer of indirection. For a project that wraps
one external API surface, that is the standard price of a sane
separation between "what the browser asked for" and "what the upstream
actually expects". The rest of this document is the operator-level
tour of that separation.

---

## 2. Architecture overview

The call chain is a straight stack. From the browser to the upstream
Honcho service, every layer has exactly one job.

```
   Browser (Angular UI)
        |
        |  X-Session-Id: <hex>           (authentication)
        |  X-Honcho-Profile-Id: <id>     (which Honcho instance)
        v
+-----------------------+
|   HonchoController    |   (controller layer; thin pass-through to the proxy service)
+-----------------------+
        |
        v
+-----------------------+
|  HonchoProxyService   |   (builds HonchoContext from the selected profile)
+-----------------------+
        |
        v
+-----------------------+
|  HonchoClientFactory  |   (selects the right HonchoClient for the requested
+-----------------------+    HonchoApiVersion; throws UnsupportedHonchoVersionException
        |                     on miss; fails fast on boot if two clients claim
        |                     the same version)
        v
+-----------------------+     +-----------------------+
|   HonchoV3Client      | ... |   HonchoV4Client      |   (one HonchoClient per
+-----------------------+     +-----------------------+    supported version; each is
        |                                              a thin dispatcher: 24 typed
        |                                              methods + 1 generic call())
        v
+-----------------------+
| HonchoProviderRegistry|   (per-HonchoClient dispatch table; sorts providers
+-----------------------+    by class name for deterministic collision handling;
        |                     filters by supportedVersions() at construction)
        |
        v
+-----------------------+     +-----------------------+     +-----------------------+
| PeersProviderV3       |     | SessionsProviderV3    | ... | WorkspaceProviderV3   |
+-----------------------+     +-----------------------+     +-----------------------+
        |                                                                      |
        |  (one HonchoProvider per resource cluster; 8 V3 providers covering    |
        |   all 24 HonchoOperation constants)                                   |
        v                                                                      v
+-----------------------------------------------------------------------+
|  RestClient  ---->  Honcho upstream  (https://api.honcho.dev or        |
|                                       per-profile baseUrl + /v3/...)   |
+-----------------------------------------------------------------------+
```

The shape is deliberately small. There are exactly three classes that
hold a `Map<HonchoApiVersion, ...>`-style dispatch table
(`HonchoClientFactory`, `HonchoClientFactory`'s internal
`clientsByVersion`, `HonchoProviderRegistry`'s internal
`providersByOperation`) and every one of them is built eagerly in the
constructor so a misconfiguration surfaces at boot, not at the first
production 500.

### 2.1 What lives at each layer

- **`HonchoController`** — the `@RestController` that maps `/api/peers/*`,
  `/api/sessions/*`, `/api/queue-status`, `/api/workspace/info`,
  `/api/search`, `/api/dream` to the proxy service. Spring MVC routes
  are declared with `@GetMapping` / `@PostMapping` / `@DeleteMapping`
  on this class. The controller does not know which Honcho API
  version it is calling.

- **`HonchoProxyService`** — builds a `HonchoContext` from the
  currently-selected `Profile` (API key, base URL, workspace id,
  Honcho user name), then delegates the call to a `HonchoClient`. It
  is the single point where per-request state (auth, workspace
  selection) crosses from the controller layer into the dispatch
  layer. Catches `HonchoCallException` and maps it to a structured
  error response for the browser.

- **`HonchoClientFactory`** — Spring-managed bean, constructor-built
  `Map<HonchoApiVersion, HonchoClient>`. `clientFor(V3)` returns the
  V3 client; `clientFor(V4)` returns the V4 client (when one is
  registered). A miss throws `UnsupportedHonchoVersionException`
  whose message lists the supported versions and points to this
  document.

- **`HonchoClient`** — pure interface (24 typed methods + 1 generic
  `call(...)` + `supportedVersions()`); one implementation per
  supported Honcho version. The current code base has
  `HonchoV3Client`; the V4 walkthrough in §6 describes what
  `HonchoV4Client` will look like.

- **`HonchoV3Client`** — the only registered `HonchoClient` in this
  build. Constructor takes `List<HonchoProvider>` (every Spring bean
  that implements `HonchoProvider`), passes them to a
  `HonchoProviderRegistry(V3, providers)`, and uses the registry
  inside every one of the 24 typed methods. The body of each typed
  method is one line: `call(op, ctx, body, pathVars, query)`.

- **`HonchoProviderRegistry`** — per-version dispatch table. The
  constructor sorts the input list by `Class.getName()` (so the
  collision outcome is deterministic regardless of Spring bean
  order), filters to providers whose `supportedVersions()` set
  contains the registry's target version, and registers every
  claimed `HonchoOperation` with `Map.putIfAbsent` (NOT `put`).
  `get(op)` returns the registered provider; on miss, throws
  `IllegalStateException` whose message lists the covered
  operations.

- **`HonchoProvider` (x8 V3 providers)** — one `@Component` per
  resource cluster. The eight V3 providers are
  `PeersProviderV3`, `PeerQueryProviderV3`, `SessionsProviderV3`,
  `MessagesProviderV3`, `WorkspaceProviderV3`, `QueueStatusProviderV3`,
  `SearchProviderV3`, and `DreamsProviderV3`. Each implements the
  `HonchoProvider` interface and owns its own `pathTemplate` and
  `httpMethod` switch expressions.

- **`RestClient` (Spring)** — the HTTP transport. Configured in
  `HttpClientConfig` with the operator-provided
  `honcho.request-timeout-ms` value (default 30 seconds). Every V3
  provider injects the same `RestClient` bean; no provider owns its
  own transport.

### 2.2 Where the per-request state lives

Every Honcho call carries the same six pieces of state:

| Field         | Source                                                                                |
| ------------- | ------------------------------------------------------------------------------------- |
| `apiKey`      | Decrypted from `honcho_profiles.api_key_encrypted` for the selected profile.          |
| `baseUrl`     | `honcho_profiles.base_url` (with trailing `/mcp` stripped by `sanitizeBase`).          |
| `workspaceId` | `honcho_profiles.workspace_id`; substituted into the `{ws}` path placeholder.         |
| `userName`    | `honcho_profiles.honcho_user_name`; sent as `X-Honcho-User-Name`.                     |
| `apiVersion`  | `honcho.api-version` globally, or `honcho_profiles.api_version` per profile.          |
| `pathVars`    | Pulled from the controller's typed method arguments (e.g. `peerId`, `sessionId`).     |

These six are bundled into a `HonchoContext` record at the top of
each call (`HonchoProxyService` is the one place that knows about
profiles) and passed through the dispatch chain as a single
parameter. Nothing downstream re-reads the profile row, the
session row, or any header.

---

## 3. Anatomy of a provider

`HonchoProvider` is a small, plain-Java interface. It carries no Spring
annotations (despite being `@Component`-friendly by intent), no
version-specific logic, and no transport assumptions. The
implementation is free to use the `HonchoClient` argument or bypass it
in favour of a directly-injected `RestClient`. The contract is the
five methods below; the rest of this section walks through them and
shows a minimal end-to-end example.

### 3.1 The five methods

| #  | Method                                                                                                  | Required? | Purpose                                                                 |
| -- | ------------------------------------------------------------------------------------------------------- | --------- | ----------------------------------------------------------------------- |
| 1  | `Set<HonchoOperation> operations()`                                                                     | Yes       | Which `HonchoOperation`s this provider handles. Non-empty, stable.      |
| 2  | `Set<HonchoApiVersion> supportedVersions()`                                                             | Yes       | Which Honcho API versions this provider knows. Usually `EnumSet.of(V3)`.|
| 3  | `Object execute(op, ctx, client, body, pathVars, queryParams)`                                           | Yes       | The actual upstream call. See §3.2 for the contract.                     |
| 4  | `default String pathTemplate(op)`                                                                       | No        | URL template for `op` relative to the workspace. Throws if not overridden.|
| 5  | `default HttpMethod httpMethod(op)`                                                                     | No        | HTTP method for `op`. Throws if not overridden.                         |

(The "7 methods" figure in some early planning docs counts `execute`'s
six arguments and `pathTemplate` / `httpMethod` as separate; the
interface itself declares five members. The plan's intent was to call
out every overridable method, not to invent extra ones.)

### 3.2 What `execute` must do

The `HonchoProvider` Javadoc spells out the implementation contract.
In one paragraph: resolve the URL via `pathTemplate(op)`, substitute
the path variables from `pathVars` and `{ws}` from `ctx.workspaceId()`,
serialize `requestBody` as JSON, apply the per-profile auth headers
(`Authorization: Bearer <apiKey>` + `X-Honcho-User-Name: <userName>`),
make the upstream call via the supplied `RestClient` (or the supplied
`HonchoClient`'s typed method, if delegating), and translate any
non-2xx response or transport failure into a `HonchoCallException` —
never let raw Spring or HTTP exceptions leak to the controller.

The return type is `Object`: Honcho's response envelopes are not
modelled in this codebase, so providers deserialize to
`Object` and the controller layer forwards the JSON through to the
browser. A `null` return means "Honcho returned 204 No Content".

### 3.3 Why multi-operation, not one-per-op

Honcho v3 has 24 endpoints but groups them naturally into about 7 to
9 resource clusters (peers, peer cards, peer query / chat / search,
sessions, session messages, session context / summaries / peers,
workspace, queue status, search, dreams). A multi-operation provider
keeps the related code — `pathTemplate` switch arm, `httpMethod`
switch arm, response shaping, error mapping — in a single class so
that, for example, all session-message operations evolve together as
Honcho changes the contract. Splitting one-per-file would scatter
that cohesion across 24 classes with no real reuse.

### 3.4 A minimal provider (annotated)

A provider that handles two `HonchoOperation`s and delegates to a
shared `RestClient` looks like this. The real `PeersProviderV3` is
the canonical reference; this is a stripped-down skeleton showing
only the parts every provider must have:

```java
package com.example.myextension.honcho.v3;

import java.net.URI;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;
import com.revytechinc.honchoinspector.honcho.HonchoCallException;
import com.revytechinc.honchoinspector.honcho.HonchoClient;
import com.revytechinc.honchoinspector.honcho.HonchoOperation;
import com.revytechinc.honchoinspector.honcho.HonchoProvider;
import com.revytechinc.honchoinspector.model.HonchoContext;

@Component
public class ExampleProviderV3 implements HonchoProvider {

    private static final Set<HonchoOperation> OPS = EnumSet.of(
        HonchoOperation.LIST_PEERS,
        HonchoOperation.CREATE_PEER
    );

    private final RestClient http;

    public ExampleProviderV3(RestClient honchoRestClient) {
        this.http = honchoRestClient;
    }

    @Override
    public Set<HonchoOperation> operations() {
        return OPS;
    }

    @Override
    public Set<HonchoApiVersion> supportedVersions() {
        return EnumSet.of(HonchoApiVersion.V3);
    }

    @Override
    public String pathTemplate(HonchoOperation op) {
        return switch (op) {
            case LIST_PEERS  -> "v3/workspaces/{ws}/peers/list";
            case CREATE_PEER -> "v3/workspaces/{ws}/peers";
            default -> throw new UnsupportedOperationException(
                "ExampleProviderV3 has no path template for " + op);
        };
    }

    @Override
    public HttpMethod httpMethod(HonchoOperation op) {
        return switch (op) {
            case LIST_PEERS, CREATE_PEER -> HttpMethod.POST;
            default -> throw new UnsupportedOperationException(
                "ExampleProviderV3 has no HTTP method for " + op);
        };
    }

    @Override
    public Object execute(
        HonchoOperation op,
        HonchoContext ctx,
        HonchoClient client,
        Object requestBody,
        Map<String, String> pathVars,
        Map<String, ?> queryParams
    ) throws HonchoCallException {
        if (!OPS.contains(op)) {
            // Registry should never call us with an op we did not claim.
            // Defensive throw keeps a misconfiguration visible.
            throw new HonchoCallException(
                "ExampleProviderV3 does not handle " + op, 501, null);
        }
        String path = pathTemplate(op)
            .replace("{ws}", ctx.workspaceId());
        URI uri = URI.create(ctx.baseUrl() + "/" + path);
        try {
            var response = http.method(httpMethod(op))
                .uri(uri)
                .headers(h -> {
                    h.setBearerAuth(ctx.apiKey());
                    h.set("X-Honcho-User-Name", ctx.userName());
                })
                .contentType(MediaType.APPLICATION_JSON);
            if (requestBody != null) {
                response = response.body(requestBody);
            }
            return response.retrieve().toEntity(Object.class).getBody();
        } catch (HttpStatusCodeException e) {
            throw new HonchoCallException(
                "Honcho returned " + e.getStatusCode() + ": "
                    + e.getResponseBodyAsString(),
                e.getStatusCode().value(),
                e.getResponseBodyAsString()
            );
        } catch (Exception e) {
            throw new HonchoCallException(
                "Cannot reach Honcho at " + ctx.baseUrl() + ": " + e.getMessage(),
                502, null
            );
        }
    }
}
```

The eight real V3 providers in this codebase are organized the same
way; `PeersProviderV3` and `SessionsProviderV3` are the longest ones
(five and seven operations respectively), and the four "small" V3
providers (`WorkspaceProviderV3`, `QueueStatusProviderV3`,
`SearchProviderV3`, `DreamsProviderV3`) are exactly the
single-operation shape shown above, with shared helpers in
`V3ProviderSupport`.

### 3.5 The `HonchoClient` argument to `execute`

The third argument to `execute` is the version's `HonchoClient`
itself — `HonchoV3Client` in the V3 case. Providers are free to
ignore it (the real V3 providers do, and use the `RestClient` they
were constructed with instead), but it is there so a provider that
needs to reach back into the client surface (for retries, for
metrics, for cross-provider calls) has a clean way to do so without
re-resolving the bean from the application context.

A provider may also use the typed `HonchoClient` method instead of
re-implementing URL construction. For example, a hypothetical
`WorkspaceProviderV3` could call `client.getWorkspaceInfo(ctx)`
rather than building the URL by hand. In practice the V3 providers
do the work themselves because the URL is two lines of code and the
typed method would be a layer of indirection that obscures the
upstream contract.

---

## 4. Adding a new endpoint to V3

This is the most common change to the layer. The seven steps below
take you from "Honcho upstream added a new endpoint" to "the
browser can call it through the proxy". Each step has an explicit
verification step, and the final step is a documentation update.

### Step 1. Add the `HonchoOperation` enum constant

Open `src/main/java/com/revytechinc/honchoinspector/honcho/HonchoOperation.java`
and append a new constant whose Javadoc states:

- the legacy controller route (`/api/...`)
- the v3 upstream path (`/v3/workspaces/{ws}/...`)
- the v2 to v3 migration note, if any (a `was GET` note, a `path
  change` note, or a `body shape change` note)

Use the existing constants as templates. Keep the
`{ws}` placeholder in the upstream path so providers can use the
shared `substitutePath` helper that fills it from
`ctx.workspaceId()`.

```java
/** GET {@code /api/peers/{id}/documents} → GET {@code /v3/workspaces/{ws}/peers/{id}/documents}. */
LIST_PEER_DOCUMENTS,
```

The enum order is declaration order; the `HonchoProviderRegistry`
uses an `EnumMap` internally, so iteration order is stable. Keep
related operations adjacent (peer ops together, session ops
together) for readability.

### Step 2. Add the method to the `HonchoClient` interface

Open `src/main/java/com/revytechinc/honchoinspector/honcho/HonchoClient.java`
and add an abstract method. Use the convention of the existing
methods: one `HonchoContext` first, the path / body arguments in
the order they appear in the controller, and `throws
HonchoCallException` last. The return type is always `Object` — do
not couple the client surface to a Honcho SDK class.

```java
/**
 * List documents attached to {@code peerId}.
 * Corresponds to {@link HonchoOperation#LIST_PEER_DOCUMENTS}.
 */
Object listPeerDocuments(HonchoContext ctx, String peerId,
                         Map<String, ?> filters) throws HonchoCallException;
```

`Map<String, ?>` is the right type for query filters: a provider
will pass them through to the upstream's query string, and the
client interface should not constrain the upstream's filter
vocabulary.

### Step 3. Add the typed method to `HonchoV3Client`

Open `src/main/java/com/revytechinc/honchoinspector/honcho/v3/HonchoV3Client.java`
and add the matching implementation. Every method body is the same
shape — one line that calls the private `call(op, ctx, body,
pathVars, query)` helper. Build `pathVars` and `queryParams` from
the typed arguments using the package-private `pathVars(k, v)`
helper that wraps a one-entry `Map.of`:

```java
@Override
public Object listPeerDocuments(HonchoContext ctx, String peerId,
                                Map<String, ?> filters) throws HonchoCallException {
    return call(HonchoOperation.LIST_PEER_DOCUMENTS, ctx, null,
                pathVars("peerId", peerId), filters);
}
```

If the new operation has no path variables (e.g. workspace-level
endpoints), pass `null` for the `pathVars` argument. If it has no
query parameters, also pass `null` for the `queryParams` argument.

### Step 4. Add the controller endpoint

Open `src/main/java/com/revytechinc/honchoinspector/controller/HonchoController.java`
and add the matching `@GetMapping` / `@PostMapping` / etc. Use the
existing methods as templates. The body is one line that delegates
to `HonchoProxyService`:

```java
@GetMapping("/api/peers/{peerId}/documents")
public ResponseEntity<Object> listPeerDocuments(
    @PathVariable String peerId,
    @RequestParam(required = false) Map<String, ?> filters
) {
    HonchoContext ctx = honchoProxyService.contextForCurrentRequest();
    HonchoClient client = honchoProxyService.clientForCurrentRequest();
    return ResponseEntity.ok(
        client.listPeerDocuments(ctx, peerId, filters));
}
```

Add the corresponding `@Operation` and `@ApiResponses` annotations
if the project is using the springdoc annotations set in T2; mirror
the existing methods' style. The OpenAPI drift check
(`OpenApiDriftCheckTest`) compares the annotations on the
controller to the entries in `docs/openapi.yaml` and fails the
build if the two diverge.

### Step 5. Add or extend a V3 provider

Two cases:

- **The new operation belongs to an existing resource cluster.**
  Open the existing `*ProviderV3` class (e.g.
  `PeersProviderV3` for the example above), add the new constant to
  the `OPS` `EnumSet`, add the path / method to the two switch
  expressions, and extend the test class with a new test method.
  No new file needed.

- **The new operation is a new resource cluster.** Create a new
  `*ProviderV3` class in
  `src/main/java/com/revytechinc/honchoinspector/honcho/v3/`. Use
  the existing `*ProviderV3` classes as templates — copy the
  structure (constructor that takes `RestClient honchoRestClient`,
  `operations()` returning a private `OPS` constant,
  `supportedVersions()` returning `EnumSet.of(V3)`, the two switch
  expressions, and the `execute` body). The new class is
  auto-discovered by Spring's component scan; no manual wiring is
  needed.

For both cases, register the new op in the `OPS` `EnumSet` (it is
easy to forget this; the registry's "no provider covers this op"
error will surface the mistake at the first test).

### Step 6. Run `mvn test`

`mvn test` runs the unit tests for every provider, the registry
collision tests, the factory version-resolution tests, and the
controller slice tests. A green test run means:

- The new `HonchoOperation` constant is reachable from the
  `HonchoClient` interface (the typed method exists and the V3
  client implements it).
- The new provider claims the new operation (the `OPS` set
  contains the constant and the `pathTemplate` / `httpMethod`
  switches have a case for it).
- The controller route exists and the springdoc annotations
  match the operation.
- The drift check between the controller and `docs/openapi.yaml`
  passes.

If any test fails, the failure message points at the missing piece
(no provider covers this op, no controller route, drift between
yaml and controller annotations, etc.). Do not skip tests to make
the build pass; fix the missing piece and re-run.

### Step 7. Update `docs/openapi.yaml`

Open `docs/openapi.yaml` and add a new `path` entry under the
existing `honcho-proxy` tag. The shape mirrors the existing entries:
`summary`, `operationId`, `parameters` (with the path variable
description and the optional query-map description), and the
`responses` block with `200`, `400`, `401`, and `502` shapes.

```yaml
/api/peers/{peerId}/documents:
  get:
    tags: [honcho-proxy]
    summary: List documents attached to a peer.
    operationId: listPeerDocuments
    parameters:
      - name: peerId
        in: path
        required: true
        schema: { type: string }
    responses:
      '200':
        description: The list of documents.
        content:
          application/json:
            schema: { type: array, items: { type: object } }
      '400': { $ref: '#/components/responses/BadRequest' }
      '401': { $ref: '#/components/responses/Unauthorized' }
      '502': { $ref: '#/components/responses/UpstreamError' }
```

`docs/openapi.yaml` is the hand-written canonical spec; the
springdoc-generated snapshot at `docs/openapi.generated.json` is
compared to it by `OpenApiDriftCheckTest`. Hand-edits to the yaml
are the source of truth. If you only update the controller
annotations, the test fails; if you only update the yaml, the
test fails. Update both.

The companion document
[`docs/regenerating-openapi.md`](regenerating-openapi.md) (when it
ships) will describe how to regenerate the generated snapshot if
that workflow becomes part of CI. For now, hand-edit
`docs/openapi.yaml` and check the drift test in `mvn test`.

---

## 5. Adding a new endpoint without forking

The provider layer is an SPI: a third party who needs a non-default
upstream (a stub for tests, a recording proxy for compliance, a
self-hosted Honcho with bespoke auth) can drop in a `@Component`
that overrides a default provider, and the registry will pick it
up at boot.

### 5.1 The "override a default provider" pattern

Two providers claim the same `HonchoOperation` (e.g.
`LIST_PEERS`). The registry sorts the input list by
`Class.getName()` and uses `Map.putIfAbsent`, so the
alphabetically-earlier class name wins. The losing provider is
silently not registered (a WARN is logged naming both contenders).
This is the "drop in" path: write a `@Component` whose class name
sorts before the default you want to override, and your
implementation wins.

```java
package com.example.acme.audit.honcho.v3;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;
import com.revytechinc.honchoinspector.honcho.HonchoCallException;
import com.revytechinc.honchoinspector.honcho.HonchoClient;
import com.revytechinc.honchoinspector.honcho.HonchoOperation;
import com.revytechinc.honchoinspector.honcho.HonchoProvider;
import com.revytechinc.honchoinspector.model.HonchoContext;

@Component
public class AcmeAuditPeersProviderV3 implements HonchoProvider {

    private static final Set<HonchoOperation> OPS = EnumSet.of(
        HonchoOperation.LIST_PEERS,
        HonchoOperation.CREATE_PEER
    );

    @Override public Set<HonchoOperation> operations()             { return OPS; }
    @Override public Set<HonchoApiVersion> supportedVersions()    { return EnumSet.of(HonchoApiVersion.V3); }

    @Override public String pathTemplate(HonchoOperation op) {
        return "v3/workspaces/{ws}/peers/list";
    }

    @Override public HttpMethod httpMethod(HonchoOperation op) {
        return HttpMethod.POST;
    }

    @Override
    public Object execute(HonchoOperation op, HonchoContext ctx,
                          HonchoClient client, Object requestBody,
                          java.util.Map<String, String> pathVars,
                          java.util.Map<String, ?> queryParams)
            throws HonchoCallException {
        // 1. Log the call for audit.
        auditLog.record(op, ctx, pathVars);
        // 2. Delegate to the default implementation.
        return client.call(op, ctx, requestBody, pathVars, queryParams);
    }
}
```

The class name `AcmeAuditPeersProviderV3` sorts before
`PeersProviderV3` (`A` < `P`), so this provider wins the
collision. The default `PeersProviderV3` is never invoked for
`LIST_PEERS` or `CREATE_PEER`. The registry logs a single WARN
line at boot naming both providers; the operator can grep for
`operation.*claimed by both` to find the override.

If the operator wants to keep the default but just decorate its
output, the same pattern applies — call `client.call(op, ctx,
...)` from inside the override, then transform the return value.
The `HonchoClient` argument is the entry point to the default
behaviour; `client.call` is the generic dispatcher, and
`client.listPeers(ctx, filters)` is the typed V3 convenience
method.

### 5.2 The "add a new operation without forking" pattern

For an operation the upstream added that is not yet in
`HonchoOperation`, the third party cannot drop in a `@Component`
that "adds" it, because the enum is a closed set referenced by
both the client interface and the controller. To extend the
operation set, the third party must:

1. Fork the project (or, if a plugin system is added later, use
   it).
2. Add a new `HonchoOperation` constant.
3. Add a typed method to `HonchoClient`.
4. Implement the typed method in `HonchoV3Client`.
5. Add the controller endpoint.
6. Add the provider.
7. Update `docs/openapi.yaml`.

This is the same seven-step process as §4. The third-party
provider class is then discovered by Spring at boot, just like
the in-tree providers. There is no plugin manifest or runtime
registration; the project conventions (the enum, the client
interface, the controller, the OpenAPI yaml) are the contract.

### 5.3 The CustomProviderSpiTest proof

`CustomProviderSpiTest` (T29) is the regression test that proves
the SPI is real. It defines a third-party `HonchoProvider`
implementation in the test source set (a class named
`CustomTestProvider` that claims one operation, e.g.
`LIST_PEERS`), builds a `HonchoProviderRegistry(V3, List.of(new
PeersProviderV3(...), new CustomTestProvider(...)))`, and asserts
that the custom provider wins the collision. The test is green
when:

- `registry.providerCount() == 2` (both providers contributed at
  least one operation, even though only one wins each op)
- `registry.covers(LIST_PEERS) == true`
- The provider returned for `LIST_PEERS` is the
  alphabetically-earlier class
- A WARN line is logged naming both contenders

If a future refactor breaks any of those assertions, the test
fails and the SPI is no longer a stable extension point.

### 5.4 What stays stable, what does not

For integrators building on top of the SPI:

- **Stable:** the `HonchoProvider` interface, the
  `HonchoOperation` enum, the `HonchoApiVersion` enum, the
  `HonchoClient` interface, the `HonchoClientFactory.clientFor`
  method, the `HonchoProviderRegistry` constructor and `get`
  method, the package name
  `com.revytechinc.honchoinspector.honcho`.
- **Unstable:** the eight concrete V3 provider class names
  (they may be renamed or refactored), the `V3ProviderSupport`
  helper class (package-private; not part of the SPI), the
  controller route paths (forwarded to the browser; the contract
  is `docs/openapi.yaml`, not the controller class).

Override a V3 provider by writing a class whose name sorts
before the default and returns the same `OPS` set as the
default for the ops you want to take over. To replace the entire
upstream (Honcho, a stub, a recording proxy), the same pattern
works — just don't delegate to `client.call`, do the work
yourself and return whatever shape the test expects.

---

## 6. Adding V4 support

Honcho `V4` is not implemented in this build. The enum constant
`HonchoApiVersion.V4` is present (T5 added it for forward
compatibility) and `HonchoApiVersion.fromString("v4")` resolves
correctly, but there is no `HonchoV4Client`, no V4 provider, and no
V4 fixture set. This section is the walkthrough for the future
task that adds V4 support; the seven steps mirror §4 with the
new-client-specific differences called out.

### Step 1. Implement `HonchoV4Client`

Create
`src/main/java/com/revytechinc/honchoinspector/honcho/v4/HonchoV4Client.java`.
The class is a near-clone of `HonchoV3Client`: it implements
`HonchoClient`, declares `@Component`, takes a
`List<HonchoProvider>` in the constructor, builds a
`HonchoProviderRegistry(V4, providers)`, and exposes the same 24
typed methods + 1 generic `call(...)` method. The body of each
typed method is one line — call the registry's `call(op, ctx,
body, pathVars, queryParams)` helper with the right
`HonchoOperation` and the right `pathVars`.

The single change from V3 is the `supportedVersions()` return
value:

```java
@Override
public Set<HonchoApiVersion> supportedVersions() {
    return EnumSet.of(HonchoApiVersion.V4);
}
```

The class name `HonchoV4Client` sorts after `HonchoV3Client`
alphabetically, but this does not matter — `HonchoClientFactory`
indexes by `HonchoApiVersion`, not by class name, and V3 and V4
are different keys. There is no collision risk as long as the
two `supportedVersions()` sets do not overlap.

### Step 2. Implement the V4 providers

For each of the 24 `HonchoOperation`s, the V4 path / method /
body shape may differ from V3. The V4 providers are versioned
copies of the V3 providers:

- Place them in
  `src/main/java/com/revytechinc/honchoinspector/honcho/v4/`.
- Annotate each with `@Component`.
- Implement `HonchoProvider`, return `EnumSet.of(V4)` from
  `supportedVersions()`.
- Update the `pathTemplate` / `httpMethod` switch arms to
  reflect the V4 contract.
- Update the `execute` body to use the V4 body envelopes (if
  Honcho changed them).

The simplest pattern is one V4 provider per resource cluster,
mirroring the V3 layout: `PeersProviderV4`,
`PeerQueryProviderV4`, `SessionsProviderV4`,
`MessagesProviderV4`, `WorkspaceProviderV4`,
`QueueStatusProviderV4`, `SearchProviderV4`,
`DreamsProviderV4`. If V4 collapses two V3 clusters into one
(e.g. merging peer query and peer search), the V4 side has
fewer providers — that is fine. The V3 providers are
unaffected; the `HonchoProviderRegistry(V3, ...)` and
`HonchoProviderRegistry(V4, ...)` are independent tables.

### Step 3. Confirm `HonchoApiVersion.V4` is present

It is — `HonchoApiVersion.java` declares `V4("v4")` (T5). No
edit to that file is needed. If a future refactor removes the
constant, this walkthrough becomes invalid; check
`HonchoApiVersion.fromString("v4")` returns `V4` before
proceeding.

### Step 4. Set the version

Two options:

- **Global default.** Set
  `honcho.api-version: v4` in `application.yml` (or
  `HONCHO_API_VERSION=v4` in the operator's drop-in
  `application.yml`). Every profile that does not override the
  version uses V4.
- **Per-profile override.** Leave the global default as V3 and
  set `api_version: v4` on the `honcho_profiles` row for the
  workspaces that should use V4. The `SchemaMigrator` (T3)
  added the `api_version` column to the schema and the
  `HonchoClientFactory.resolveVersion(overrideOrNull,
  fallback)` helper is the single point that reads it.

For a mixed fleet (some V3 workspaces, some V4 workspaces), the
per-profile override is the intended path. For a clean
cutover, the global default is simpler.

### Step 5. Add V4 fixtures

The V3 fixture set under
`src/test/resources/fixtures/honcho/v3/` was captured against
the public Honcho v3 OpenAPI spec. The V4 fixture set goes in
`src/test/resources/fixtures/honcho/v4/`, one JSON per
operation, each with a `_meta` block recording
`honcho_version`, capture timestamp, method, endpoint, and
synthetic flag.

The capture script pattern is the same as the V3 one (see
[`docs/regenerating-fixtures.md`](regenerating-fixtures.md) for
the V3 version; the V4 capture script is a one-line fork that
points at the V4 upstream and writes to the V4 fixtures
directory). Real-mode captures from a live V4 Honcho are
preferred; synthetic-mode captures derived from the V4 OpenAPI
spec are acceptable for CI.

### Step 6. Run integration tests

`mvn test` with the V4 providers and fixtures in place asserts
that:

- `HonchoV4Client` is discovered by Spring (the `@Component`
  annotation works).
- `HonchoClientFactory` indexes V4 alongside V3 (the
  `supportedVersions()` claim is honored).
- The V4 providers cover all 24 `HonchoOperation`s (the
  registry's `coveredOperations()` set is full).
- The V4 fixtures match the V4 provider request shapes (the
  fixture-driven workflow tests pass).

If `HonchoV4Client` is missing a typed method that V3 has, the
interface compile fails — that is the cheapest signal. If a V4
provider's `pathTemplate` does not have a case for an operation
in its `OPS` set, the registry collision test or the provider
unit test catches it. If a V4 fixture does not match the V4
provider's request body shape, the workflow test fails with a
JSON parse error.

### Step 7. Update `docs/openapi.yaml`

The hand-written OpenAPI document does not change shape between
V3 and V4 — the browser-facing routes (`/api/peers/*`,
`/api/sessions/*`, etc.) are the same, and the contract with
the UI is the same. What does change is the `x-honcho-api-version`
metadata: a top-level `x-honcho-api-version-differences`
extension records, for each operation, whether the V4 upstream
differs from the V3 upstream (different path, different verb,
different body, different response envelope, etc.).

```yaml
x-honcho-api-version-differences:
  v3-vs-v4:
    - operation: LIST_PEERS
      v3: { method: POST, path: '/v3/workspaces/{ws}/peers/list' }
      v4: { method: POST, path: '/v4/workspaces/{ws}/peers/list' }
      breaking: false
      note: 'Path prefix only; the body and response are unchanged.'
    - operation: SEARCH_MESSAGES
      v3: { method: POST, path: '/v3/workspaces/{ws}/search' }
      v4: { method: POST, path: '/v4/workspaces/{ws}/search' }
      breaking: false
      note: 'Body envelope gained an optional `filters` field; ignored if absent.'
```

If the V4 contract is a breaking change for an operation, the
note calls that out explicitly and references the Honcho v4
migration guide. The hand-written spec stays the source of
truth for the browser-facing contract; the V4 differences
extension is a machine-readable sidecar for operators running a
mixed fleet.

### 6.1 What does not change in V4

- `HonchoOperation` — the 24 enum constants are version-agnostic
  identifiers. A V4 op with the same name as a V3 op is the
  same op to the client surface; only the path / method / body
  underneath differ.
- `HonchoClient` — the interface stays stable. V3 and V4
  implement the same methods.
- `HonchoProvider` — the interface stays stable. The two
  defaults (`pathTemplate`, `httpMethod`) and the three
  abstracts (`operations`, `supportedVersions`, `execute`)
  apply to V4 unchanged.
- The controller layer — `HonchoController` does not know which
  Honcho version it is calling; the proxy service is the only
  place that resolves the version.
- The proxy service — `HonchoProxyService` reads the
  `api_version` from the profile, calls
  `HonchoClientFactory.clientFor(version)`, and delegates. The
  V3 / V4 dispatch is opaque above this point.

This is the point of the layer: a Honcho major version is a
scaling change for the upstream, not a scaling change for the
code that wraps it.

---

## 7. Troubleshooting

Four errors account for the vast majority of operator questions
about this layer. Each section below shows the error message,
the cause, the verification, and the fix. All four are
configuration-time / boot-time errors, not runtime 500s — the
layer is designed to fail loudly when it is misconfigured.

### 7.1 `UnsupportedHonchoVersionException`

**Symptom.** The proxy returns a 500 with a body containing
"Honcho version V4 is not supported by this build. Supported
versions: [V2, V3]. See docs/honcho-providers.md for how to add
support."

**Cause.** The operator set `honcho.api-version: v4` (or a
profile row has `api_version: v4`), but no `HonchoClient` bean
claims `HonchoApiVersion.V4` in this build. The
`HonchoClientFactory` throws
`UnsupportedHonchoVersionException` on the lookup; the proxy
catches it and returns the 500.

**Verification.**

```bash
# Confirm V4 is requested somewhere
grep -r "api-version\|api_version" /etc/honcho-inspector/
sqlite3 /etc/honcho-inspector/honcho-inspector.db \
  "SELECT id, label, api_version FROM honcho_profiles;"
```

**Fix.** Either downgrade the version to V3 (the only version
with a registered client in this build) or implement V4 — see
§6 for the seven-step walkthrough. If you implement V4, confirm
`HonchoClientFactory.clientFor(V4)` returns a non-null client
before restarting the proxy.

### 7.2 "No HonchoProvider registered for operation ..." (in logs)

**Symptom.** The application starts, the registry is built, and
the first call to a particular `HonchoOperation` throws an
`IllegalStateException` whose message starts with `"No
HonchoProvider registered for operation ..."`. The message
lists every operation the registry DOES cover and the target
version.

**Cause.** A controller method calls `client.call(op, ...)`
with an op that the registry does not cover. The most common
cause is a controller that uses a new `HonchoOperation` constant
before the matching provider class is added; the second most
common is a typo in the constant name (e.g. `LIST_PEER` instead
of `LIST_PEERS`).

**Verification.**

```bash
# The exception message lists what IS covered; cross-check against HonchoOperation.java
grep -E 'public enum HonchoOperation' \
  src/main/java/com/revytechinc/honchoinspector/honcho/HonchoOperation.java
```

**Fix.** Add a provider that claims the missing operation
(§4 step 5), or fix the constant name in the controller
method.

### 7.3 Provider collision (WARN log at boot)

**Symptom.** At application startup, a WARN line of the form:

```
HonchoProviderRegistry(v3) operation LIST_PEERS claimed by both
com.example.acme.audit.honcho.v3.AcmeAuditPeersProviderV3 and
com.revytechinc.honchoinspector.honcho.v3.PeersProviderV3;
first-registered (alphabetically by class name) wins, keeping
com.example.acme.audit.honcho.v3.AcmeAuditPeersProviderV3
```

**Cause.** Two `HonchoProvider` beans claim the same
`HonchoOperation`. The registry's deterministic collision rule
sorts the input list by `Class.getName()` and uses
`Map.putIfAbsent`; the alphabetically-earlier class name wins.

**Verification.** Confirm the WINNING provider is the one you
want. `AcmeAuditPeersProviderV3` sorts before `PeersProviderV3`
(`A` < `P`) and wins; this is the intended "third-party override"
pattern from §5. If the LOSING provider is the one you wanted
to win, rename it (e.g. `ZPeersProviderV3` sorts last and will
never win against anything else).

**Fix.** Two cases:

- **The collision is intentional.** It is the override pattern
  from §5; the WARN is informational. No action needed.
- **The collision is accidental.** A new provider accidentally
  claims an operation it did not intend to take over. Edit the
  provider's `OPS` set to remove the offending constant, or
  split the provider into two classes (one per resource
  cluster) so the cluster that owns the op wins by default.

### 7.4 Live test skipped (env vars missing)

**Symptom.** A subset of the test suite (the live-Honcho
integration tests) prints something like
`[capture] Honcho URL: <unset>; using synthetic fixtures` and
the `mvn test` summary shows the live tests as "skipped" rather
than "passed".

**Cause.** The live tests require the operator to set
`HONCHO_LIVE_TEST=1` and `HONCHO_LIVE_WORKSPACE_ID=<ws>` (and
`HONCHO_LIVE_API_KEY=<key>`) in the environment. Without
those, the test harness falls back to synthetic-mode fixtures
generated from the public Honcho OpenAPI spec.

**Verification.**

```bash
# Confirm the env vars are set
env | grep HONCHO_LIVE
# Confirm the live tests are present
grep -rE "HONCHO_LIVE_TEST|HONCHO_LIVE_WORKSPACE_ID" \
  src/test/java/
```

**Fix.** Set the env vars and re-run:

```bash
export HONCHO_LIVE_TEST=1
export HONCHO_LIVE_WORKSPACE_ID=<your-workspace-id>
export HONCHO_LIVE_API_KEY=<your-honcho-bearer-token>
mvn test
```

The synthetic-mode fallback is intentional — the CI environment
does not have a live Honcho, so the default is "synthetic is
fine". Operators who want to verify their real Honcho is
behaving correctly can flip the env vars on for a single test
run.

### 7.5 Other errors worth knowing about

These are not in the "four common errors" list but are worth
recognising when you see them:

- **`HonchoCallException: 502 Cannot reach Honcho at ...`** —
  the proxy tried to call Honcho and the connection failed
  (DNS error, connection refused, TLS handshake failure, read
  timeout). Check the upstream URL in the profile and the
  network reachability from the proxy host. The
  `honcho.request-timeout-ms` setting (default 30s) controls
  the read timeout; a slower upstream may need a higher value.

- **`HonchoCallException: 400 Honcho returned 400: ...`** —
  Honcho rejected the request body. This is usually a body
  shape change between Honcho minor versions, or a controller
  that is sending a field Honcho no longer recognises. The
  captured body is truncated to 500 characters in the error
  response; check the Honcho upstream logs for the full
  detail.

- **`HonchoCallException: 401 Honcho returned 401: ...`** —
  the API key is rejected. Check the `api_key_encrypted`
  column was not corrupted (re-enter the key via
  `POST /api/profiles` if needed) and that the
  `HONCHO_CRYPTO_KEY` env var is the same value that was used
  to encrypt the column (a key change requires re-creating
  all profiles).

---

## 8. Strict mode

`honcho.providers.strict-mode` is a boolean configuration knob
on the `HonchoProperties.Providers` record. The default value
is `false`; setting it to `true` is intended to enable a
runtime check that warns the operator when a `HonchoOperation`
is dispatched to a provider that does not explicitly claim it.

### 8.1 What it does (and what it does not, in this build)

The config flag is plumbed end to end — it is bound from
`application.yml` / `HONCHO_PROVIDERS_STRICT_MODE` into
`HonchoProperties.Providers.strictMode` and is queryable from
anywhere in the application context. What is **not** in this
build is the runtime enforcement: no bean currently reads
`strictMode` and changes its behaviour based on the value. The
intent of the flag is documented in the Phase 1 plan ("refuse
to dispatch an op to a provider that does not claim it"); the
implementation is a future task.

The current behaviour is:

- A provider that claims `LIST_PEERS` will be invoked for
  `LIST_PEERS` calls (good).
- A provider that does NOT claim `LIST_PEERS` will also be
  invoked for `LIST_PEERS` calls if its class is
  alphabetically-earlier than the one that does claim it (bad,
  but already caught by the WARN log at boot).
- The provider's own `execute` method is the last line of
  defence: it should `throw new HonchoCallException("Provider
  does not handle " + op, 501, null)` for any op it did not
  claim, and the real V3 providers do this.

When strict-mode enforcement lands, the planned behaviour is:
when `honcho.providers.strict-mode: true`, the registry refuses
to register a provider whose `OPS` set contains an operation
it does not actually implement (e.g. an empty `pathTemplate`
switch arm for that op). The check fires at construction time,
so a misconfigured provider causes a `IllegalStateException`
at boot rather than a 501 at the first call.

### 8.2 When to enable it

- **Production:** enable it. A misconfigured provider in
  production is a 5xx-causing regression; catching it at boot
  is strictly better than catching it at the first call.
  Once the runtime enforcement lands, the flag will be the
  only way to opt in to the strict check, and the default
  (`false`) is for the migration window during which the
  existing V3 providers are audited against the new check.

- **Local dev / CI:** leave it at the default (`false`). The
  strict check makes it harder to develop a new provider in
  isolation (the provider class is only valid once its
  `pathTemplate` / `httpMethod` switches are complete).
  Local dev is the right place to develop a new provider
  without strict mode; CI is the right place to run the test
  suite with strict mode enabled to catch regressions.

To enable strict mode today (as a no-op until the runtime
check lands):

```yaml
honcho:
  providers:
    strict-mode: true
```

Or, equivalently:

```bash
export HONCHO_PROVIDERS_STRICT_MODE=true
```

### 8.3 Verification

Once the runtime enforcement lands, the verification is:

```bash
HONCHO_PROVIDERS_STRICT_MODE=true mvn test
# Expect: BUILD SUCCESS; the V3 provider tests pass with strict mode on.
# If a provider's OPS set lists an op whose pathTemplate / httpMethod
# switch does not have a case, the registry throws at construction and
# the test class fails to load.
```

Until the enforcement lands, the verification is that the
config flag is bound and readable:

```bash
mvn -B test -Dtest=HonchoPropertiesTest
# Expect: at least one test asserts the strictMode field is wired
# to honcho.providers.strict-mode in application.yml.
```

---

## 9. Phase 2 forward-compat notes

The provider layer is the surface that the Phase 2 work
(orgs, sharing, stats, reports) will extend. This section
records the design choices the layer makes specifically to
accommodate those features without breaking the existing
contract.

### 9.1 Orgs and sharing

Phase 2 plans to introduce multi-tenant orgs and
profile-sharing semantics. From the provider layer's
perspective, "which user / org / workspace owns this call" is
already a `HonchoContext` field (`workspaceId`); adding more
identifiers (e.g. `orgId`, `tenantId`) is a non-breaking
extension to the record. The path templates include `{ws}` as
the only mandatory substitution; a Phase 2 provider that needs
to substitute `{orgId}` does so by adding it to the `OPS`
constant and to the `pathTemplate` switch. The registry does
not care which path placeholders exist — it only cares that
the provider's `pathTemplate` returns a non-null string.

For profile sharing, the key invariant is that the
`HonchoContext` is built from the per-request profile and
never read from a global. A Phase 2 controller that selects a
profile for "the calling user's org" is responsible for
passing the right `Profile` to the `HonchoProxyService`; the
provider layer does not need to change. The `HonchoClient`
interface stays version-only — it does not see orgs.

### 9.2 Stats and reports

Phase 2 will add stats and report-generation endpoints.
These are read-mostly operations on the upstream's analytics
surface. From the provider layer's perspective, they are
new `HonchoOperation` constants + a new V3 provider (or V4
provider, depending on the upstream's analytics contract) +
new controller routes. The seven-step walkthrough in §4
applies verbatim. The shared `pathTemplate` /
`httpMethod` switch pattern is well suited to a provider that
handles 3-5 analytics endpoints; the package-private
`V3ProviderSupport` helper is the natural place to add
analytics-specific URL construction (e.g. date-range
substitution for `?from=...&to=...` query parameters).

The provider layer's contract is that every `HonchoOperation`
is version-agnostic — the enum is a stable identity token. If
Phase 2 introduces a stats operation that is fundamentally
version-specific (e.g. the v3 stats endpoint uses GET but the
v4 stats endpoint uses POST), the same `HonchoOperation`
constant is implemented by two different providers in two
different version packages. The `HonchoClient` interface does
not change; the V3 and V4 client implementations are free to
interpret the same constant differently.

### 9.3 Multi-region and failover

A future requirement may be "if the primary Honcho upstream
is unreachable, fall back to a secondary." From the provider
layer's perspective, this is a transport-layer concern — the
`HttpClientConfig` (or its Phase 2 successor) is the right
place for it. The `RestClient` bean is injected into every
provider; a future change that wraps the `RestClient` in a
retry-and-failover client does not touch the provider classes.

For a stronger separation, the provider layer could grow a
`HonchoClientFactory` policy: `clientFor(V3, primaryUrl)`
returns a `HonchoClient` that retries against `secondaryUrl`
on transport failure. The provider layer's `execute` method
already maps transport failures to `HonchoCallException` with
status 502; a retry wrapper above the `RestClient` would
catch the exception and retry. No provider change is needed.

### 9.4 New Honcho operations the upstream has not yet shipped

The provider layer is forward-compatible with new
`HonchoOperation`s the upstream has not yet added. A future
Honcho release that adds, say, `LIST_PEER_DOCUMENTS` can be
added to the layer with the seven steps in §4, and the layer
will support it from the next release onwards. A Honcho
release that ADDS an operation to the v3 contract (without
bumping to v4) is a non-event for the provider layer — the
operation becomes a new enum constant + a new provider class
+ a new controller route, exactly as in §4.

### 9.5 What stays stable through Phase 2

The following are stable contracts that Phase 2 will not
change:

- `HonchoProvider` — the 5-method interface.
- `HonchoOperation` — the 24 enum constants, in declaration
  order.
- `HonchoApiVersion` — the three enum constants (V2, V3, V4)
  and their `pathPrefix()` values.
- `HonchoClient` — the 24 typed method signatures + the
  generic `call(...)` + `supportedVersions()`.
- `HonchoClientFactory` — the `clientFor(version)` and
  `resolveVersion(overrideOrNull, fallback)` methods.
- `HonchoProviderRegistry` — the constructor signature and
  the deterministic collision rule.
- `HonchoCallException` — the three fields (message, status,
  body) and the construction contract.
- The `HonchoContext` record — the six fields (apiKey,
  baseUrl, workspaceId, userName, apiVersion, plus any
  Phase 2 fields added as additional optional fields).

If a Phase 2 change requires breaking one of these contracts,
that is a sign the change belongs in a new module (e.g. a
`HonchoProvider2` interface, a new `HonchoOperation2` enum),
not a refactor of the existing layer.

---

## Appendix A. Quick reference

### A.1 The eight V3 providers

| Provider class              | Operations claimed                                                                                  |
| --------------------------- | ---------------------------------------------------------------------------------------------------- |
| `PeersProviderV3`           | `LIST_PEERS`, `CREATE_PEER`, `GET_PEER_CARD`, `UPDATE_PEER_CARD`, `GET_REPRESENTATION`               |
| `PeerQueryProviderV3`       | `PEER_CHAT`, `SEARCH_PEERS`, `LIST_PEER_CONCLUSIONS`, `LIST_PEER_SESSIONS`, `QUERY_PEER_CONCLUSIONS` |
| `SessionsProviderV3`        | `LIST_SESSIONS`, `CREATE_SESSION`, `GET_SESSION`, `DELETE_SESSION`, `GET_SESSION_CONTEXT`, `GET_SESSION_SUMMARIES`, `GET_SESSION_PEERS` |
| `MessagesProviderV3`        | `LIST_SESSION_MESSAGES`, `ADD_MESSAGE`, `SEARCH_SESSION_MESSAGES`                                   |
| `WorkspaceProviderV3`       | `GET_WORKSPACE_INFO`                                                                                |
| `QueueStatusProviderV3`     | `GET_QUEUE_STATUS`                                                                                  |
| `SearchProviderV3`          | `SEARCH_MESSAGES`                                                                                   |
| `DreamsProviderV3`          | `SCHEDULE_DREAM`                                                                                    |

Total: 8 distinct providers, 24 `HonchoOperation`s covered,
one `HonchoApiVersion` (`V3`) per provider.

### A.2 Files that matter

- `src/main/java/com/revytechinc/honchoinspector/honcho/HonchoProvider.java` —
  the interface (§3).
- `src/main/java/com/revytechinc/honchoinspector/honcho/HonchoClient.java` —
  the version-agnostic client surface.
- `src/main/java/com/revytechinc/honchoinspector/honcho/HonchoV3Client.java` —
  the V3 client (§3, §4).
- `src/main/java/com/revytechinc/honchoinspector/honcho/HonchoClientFactory.java` —
  the version dispatch table (§2).
- `src/main/java/com/revytechinc/honchoinspector/honcho/HonchoProviderRegistry.java` —
  the per-version op dispatch table (§2, §5).
- `src/main/java/com/revytechinc/honchoinspector/honcho/HonchoOperation.java` —
  the 24 op constants (§1, §4).
- `src/main/java/com/revytechinc/honchoinspector/honcho/HonchoApiVersion.java` —
  the V2 / V3 / V4 enum (§1, §6).
- `src/main/java/com/revytechinc/honchoinspector/honcho/UnsupportedHonchoVersionException.java` —
  the "no client for this version" error (§7).
- `src/main/java/com/revytechinc/honchoinspector/honcho/v3/PeersProviderV3.java` —
  the canonical "small" V3 provider (§3).
- `src/main/java/com/revytechinc/honchoinspector/honcho/v3/SessionsProviderV3.java` —
  the canonical "medium" V3 provider.
- `src/main/java/com/revytechinc/honchoinspector/honcho/v3/V3ProviderSupport.java` —
  the shared V3 plumbing.
- `src/main/java/com/revytechinc/honchoinspector/config/HonchoProperties.java` —
  the `honcho.providers.strict-mode` config (§8).
- `docs/openapi.yaml` — the hand-written OpenAPI spec (§4).
- `docs/regenerating-openapi.md` — the OpenAPI regeneration guide (when it ships; see §4 step 7).
- `docs/regenerating-fixtures.md` — the v3 fixture capture script (§6 step 5).

### A.3 One-liner sanity checks

```bash
# Count the providers (should be 8 for V3, plus any third-party additions)
find src/main/java -name '*ProviderV3.java' | wc -l

# Count the operations (should be 24)
grep -cE '^\s+[A-Z_]+,' \
  src/main/java/com/revytechinc/honchoinspector/honcho/HonchoOperation.java

# Confirm V4 is in the enum (should be 1)
grep -E '^\s+V4\(' \
  src/main/java/com/revytechinc/honchoinspector/honcho/HonchoApiVersion.java

# Run the provider layer's tests
mvn -B test -Dtest='HonchoProviderRegistryTest,HonchoClientFactoryTest,*ProviderV3Test'
```

If all four checks return the expected values, the layer is in
a known-good state. If any return a different value, the
section above the check explains how to fix it.

### A.4 See also

- [`docs/openapi.yaml`](openapi.yaml) — the canonical hand-written OpenAPI spec for the proxy.
- [`docs/regenerating-openapi.md`](regenerating-openapi.md) — the OpenAPI regeneration workflow (T22; when it ships).
- [`docs/regenerating-fixtures.md`](regenerating-fixtures.md) — the V3 fixture capture workflow.
- [`docs/SECURITY.md`](SECURITY.md) — threat model, audit findings, hardening checklist.
- [`docs/reverse-proxy.md`](reverse-proxy.md) — nginx / Apache / Caddy reverse-proxy configs.
- [`README.md`](../README.md) — quick start, configuration, API surface.
