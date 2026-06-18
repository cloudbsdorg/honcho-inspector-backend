# Regenerating Honcho v3 Fixtures

Honcho v3 fixtures under `src/test/resources/fixtures/honcho/v3/` are
captured JSON responses used by `HonchoMockConfig` (Phase-1 T23) to drive
downstream workflow tests (T24–T26). The fixtures are committed to git and
checked in for reproducibility; this document explains how to regenerate them.

## TL;DR

```bash
# Real mode (default): tries to call a real Honcho instance, falls back to synthetic on failure
./scripts/capture-honcho-fixtures.sh

# Force synthetic mode (no network; useful in CI / sandboxes)
HONCHO_SYNTHETIC=1 ./scripts/capture-honcho-fixtures.sh
```

The script writes one fixture file per Honcho v3 operation to
`src/test/resources/fixtures/honcho/v3/*.json`. Each file has a `_meta`
block (captured-at timestamp, Honcho version, endpoint, method, synthetic
flag) and a `data` block holding the captured response body.

## What the script produces

| Fixture file                        | HonchoOperation            | Method | Honcho v3 endpoint                                              |
|-------------------------------------|----------------------------|--------|-----------------------------------------------------------------|
| `create-peer.json`                  | `CREATE_PEER`              | POST   | `/v3/workspaces/{ws}/peers`                                     |
| `list-peers.json`                   | `LIST_PEERS`               | POST   | `/v3/workspaces/{ws}/peers/list`                                |
| `list-peers-post.json`              | `LIST_PEERS` (alias)       | POST   | `/v3/workspaces/{ws}/peers/list`                                |
| `get-peer-card.json`                | `GET_PEER_CARD`            | GET    | `/v3/workspaces/{ws}/peers/{peerId}/card`                       |
| `update-peer-card.json`             | `UPDATE_PEER_CARD`         | POST   | `/v3/workspaces/{ws}/peers/{peerId}/card`                       |
| `get-peer-representation.json`      | `GET_REPRESENTATION`       | GET    | `/v3/workspaces/{ws}/peers/{peerId}/representation`             |
| `representation.json`               | `GET_REPRESENTATION` (alias) | GET  | `/v3/workspaces/{ws}/peers/{peerId}/representation`             |
| `list-peer-conclusions.json`        | `LIST_PEER_CONCLUSIONS`    | GET    | `/v3/workspaces/{ws}/peers/{peerId}/conclusions`                |
| `query-peer-conclusions.json`       | `QUERY_PEER_CONCLUSIONS`   | POST   | `/v3/workspaces/{ws}/peers/{peerId}/conclusions/query`          |
| `list-peer-sessions.json`           | `LIST_PEER_SESSIONS`       | GET    | `/v3/workspaces/{ws}/peers/{peerId}/sessions`                   |
| `peer-chat.json`                    | `PEER_CHAT`                | POST   | `/v3/workspaces/{ws}/peers/{peerId}/chat`                       |
| `peer-search.json`                  | `SEARCH_PEERS`             | POST   | `/v3/workspaces/{ws}/peers/{peerId}/search`                     |
| `create-session.json`               | `CREATE_SESSION`           | POST   | `/v3/workspaces/{ws}/sessions`                                  |
| `list-sessions.json`                | `LIST_SESSIONS`            | POST   | `/v3/workspaces/{ws}/sessions`                                  |
| `list-sessions-post.json`           | `LIST_SESSIONS` (alias)    | POST   | `/v3/workspaces/{ws}/sessions`                                  |
| `get-session.json`                  | `GET_SESSION`              | GET    | `/v3/workspaces/{ws}/sessions/{sessionId}`                      |
| `get-session-peers.json`            | `GET_SESSION_PEERS`        | GET    | `/v3/workspaces/{ws}/sessions/{sessionId}/peers`                |
| `add-peer-to-session.json`          | (HTTP-level)               | POST   | `/v3/workspaces/{ws}/sessions/{sessionId}/peers`                |
| `get-session-context.json`          | `GET_SESSION_CONTEXT`      | GET    | `/v3/workspaces/{ws}/sessions/{sessionId}/context`              |
| `get-session-summaries.json`        | `GET_SESSION_SUMMARIES`    | GET    | `/v3/workspaces/{ws}/sessions/{sessionId}/summaries`            |
| `list-session-messages.json`        | `LIST_SESSION_MESSAGES`    | GET    | `/v3/workspaces/{ws}/sessions/{sessionId}/messages`             |
| `get-session-message.json`          | (HTTP-level)               | GET    | `/v3/workspaces/{ws}/sessions/{sessionId}/messages/{messageId}` |
| `add-message.json`                  | `ADD_MESSAGE`              | POST   | `/v3/workspaces/{ws}/sessions/{sessionId}/messages`             |
| `search-session-messages.json`      | `SEARCH_SESSION_MESSAGES`  | POST   | `/v3/workspaces/{ws}/sessions/{sessionId}/search`              |
| `queue-status.json`                 | `GET_QUEUE_STATUS`         | GET    | `/v3/workspaces/{ws}/queue-status`                              |
| `workspace-info.json`               | `GET_WORKSPACE_INFO`       | GET    | `/v3/workspaces/{ws}`                                           |
| `search-messages.json`              | `SEARCH_MESSAGES`          | POST   | `/v3/workspaces/{ws}/search`                                    |
| `schedule-dream.json`               | `SCHEDULE_DREAM`           | POST   | `/v3/workspaces/{ws}/peers/{peerId}/dreams`                     |
| `list-conclusions.json`             | (workspace-level)          | GET    | `/v3/workspaces/{ws}/conclusions`                               |

Total: **29 fixtures** covering all 24 `HonchoOperation` constants plus
five HTTP-level variants the v3 provider set uses.

## Real vs synthetic mode

The script has two modes:

### Real mode (default)

1. Probes `GET ${HONCHO_URL}/v3/workspaces/${WORKSPACE_ID}` with the auth
   headers. A `200`/`201`/`404`/`405` response means Honcho is reachable.
   A `401`/`403` or transport failure switches to synthetic mode for the
   remainder of the run.
2. If reachable, makes a real `POST /v3/workspaces/{ws}/peers` to create a
   peer named `fixture-capture-${EPOCH}` (timestamp suffix for
   idempotency / no collisions).
3. Creates a session for that peer.
4. Adds one message to the session.
5. Walks every list/get endpoint covered by Phase 1, capturing the
   response body into `src/test/resources/fixtures/honcho/v3/*.json`.
6. Sanitizes any `Bearer <token>` literals to `Bearer <REDACTED>` so
   secrets never reach the filesystem.
7. Best-effort teardown: deletes the session and peer at the end.

If any single call fails (HTTP 4xx/5xx, network error, validation error),
the script falls back to a synthetic fixture for **that endpoint only**
and continues. The resulting fixture is tagged with `_meta.synthetic: true`
and a `_meta.synthetic_reason` describing the failure.

### Synthetic mode (`HONCHO_SYNTHETIC=1`)

Skips all HTTP calls. Generates representative fixture JSON whose shapes
are derived from the official Honcho v3 OpenAPI spec
(https://honcho.dev/docs/v3/openapi.json). Useful for CI / sandboxes
where a live Honcho is not available. Every fixture is tagged with
`_meta.synthetic: true`.

The synthetic generator covers:

- **`Peer`** — `id`, `workspace_id`, `created_at`, `metadata`, `configuration`
- **`Session`** — `id`, `is_active`, `workspace_id`, `created_at`, `metadata`, `configuration`
- **`Message`** — `id`, `content`, `peer_id`, `session_id`, `workspace_id`, `metadata`, `created_at`, `token_count`
- **`Conclusion`** — `id`, `content`, `observer_id`, `observed_id`, `session_id`, `created_at`
- **`Page[T]` envelope** — `items`, `total`, `page`, `size`, `pages`
- **`PeerCardResponse`** — `{ "peer_card": [...] }`
- **`RepresentationResponse`** — `{ "representation": "..." }`
- **`QueueStatus`** — `total_work_units`, `completed_work_units`, `in_progress_work_units`, `pending_work_units`, `sessions`
- **`SessionContext`** — `id`, `messages`, `summary`, `peer_representation`, `peer_card`
- **`SessionSummaries`** — `id`, `short_summary`, `long_summary`
- **`Workspace`** — `id`, `metadata`, `configuration`, `created_at`

These are exactly the response shapes declared in the public Honcho v3
OpenAPI spec (`info.version = "3.0.7"` as of capture time).

## Environment variables

| Var               | Default                          | Notes                                                  |
|-------------------|----------------------------------|--------------------------------------------------------|
| `HONCHO_URL`      | `https://honcho.cloudbsd.org`    | Honcho upstream base URL                               |
| `HONCHO_API_KEY`  | (read from `~/.config/opencode/opencode.json`) | Bearer token for Honcho. Required for real mode. |
| `HONCHO_USER_NAME`| `mlapointe` (or from opencode.json) | Value of the `X-Honcho-User-Name` header              |
| `WORKSPACE_ID`    | `default`                        | Honcho workspace id                                    |
| `HONCHO_SYNTHETIC`| unset                            | Set to `1` to skip real-Honcho probing                 |
| `HONCHO_VERSION`  | `3.0.7`                          | Recorded in every fixture's `_meta.honcho_version`     |
| `FIXTURE_DIR`     | `src/test/resources/fixtures/honcho/v3` | Output directory                                  |

The capture script reads the JWT and user name from
`~/.config/opencode/opencode.json` if env vars are unset. The relevant
keys are:

```json
{
  "mcp": {
    "honcho": {
      "type": "remote",
      "url": "https://mcp.honcho.cloudbsd.org/",
      "headers": {
        "Authorization": "Bearer eyJhbG...",
        "X-Honcho-User-Name": "mlapointe"
      }
    }
  }
}
```

## Adding a new fixture

To add coverage for a new Honcho endpoint:

1. Append a new entry to one of the `GET_OPS` or `POST_OPS` arrays in
   `scripts/capture-honcho-fixtures.sh`. The format is:

   ```
   "<output-filename>|<HTTP method>|<resolved path>|<endpoint label>[|<body>]"
   ```

   Example (POST `/v3/workspaces/{ws}/peers/{peerId}/conclusions` query — already covered):

   ```bash
   "query-peer-conclusions|POST|v3/workspaces/${WS}/peers/${PEER_NAME}/conclusions/query|POST /v3/workspaces/{ws}/peers/{peerId}/conclusions/query|{\"query\":\"hello\",\"top_k\":5}"
   ```

2. If the fixture needs a special response shape, add a key to the
   `DATA` dict inside the `write_synthetic` python heredoc. Match the
   shape to the OpenAPI spec component schema.

3. Re-run the script and commit the new fixture.

## Acceptance checks

The script self-verifies before exiting:

```bash
$ ./scripts/capture-honcho-fixtures.sh
[capture] Honcho URL:    https://honcho.cloudbsd.org
[capture] Workspace ID:  default
[capture] mode: REAL (Honcho is reachable)
[capture]   wrote create-peer.json (410 bytes)
...
[capture] done. Wrote 29 fixture files to src/test/resources/fixtures/honcho/v3
[capture] OK: 29 fixtures, no leaked bearer tokens
```

Exit code is non-zero if:

- fewer than 15 fixtures were written, **or**
- any fixture contains a `Bearer <20+ chars>` literal (sanitizer miss)

External verification commands (also in the QA scenarios in the plan):

```bash
# All fixtures are valid JSON
for f in src/test/resources/fixtures/honcho/v3/*.json; do
  jq empty "$f" || echo "BAD: $f"
done

# All fixtures have a _meta block
for f in src/test/resources/fixtures/honcho/v3/*.json; do
  jq -e '._meta' "$f" > /dev/null || echo "MISSING: $f"
done

# No Bearer tokens leaked
grep -rE 'Bearer [A-Za-z0-9._-]{20,}' src/test/resources/fixtures/honcho/v3/
```

## Security notes

- The capture script writes `Bearer <REDACTED>` into fixtures even when
  real Honcho is used — any JWT that appears in a response is sanitized
  via a jq `walk(...)` pass before the fixture is written.
- Generated peer / session names include `${EPOCH}` so re-runs don't
  collide with existing data on a real Honcho.
- The script does not print or persist the API key — it lives only in
  the env / opencode config and is dropped from the bash process when
  the script exits.

## When to regenerate

Regenerate fixtures when:

- A new HonchoOperation is added (the existing script needs an entry).
- The Honcho v3 OpenAPI spec changes shape (e.g. a new field added to
  `Peer`, `Session`, `Message`).
- An existing fixture is stale and downstream workflow tests are
  failing on a JSON parse mismatch.

Don't regenerate for:

- Changes to the HonchoInspectorBackend controller paths (the fixtures
  record upstream Honcho responses, not our proxy paths).
- Java refactors that don't touch `HonchoOperation` or the
  v3 providers' `pathTemplate` / `httpMethod` mappings.

## Related

- `docs/openapi.yaml` — hand-written spec for the proxy API (T19)
- `docs/openapi.generated.json` — springdoc-generated snapshot (T20)
- Phase 1 plan: `.sisyphus/plans/phase-1-openapi-and-workflow-tests.md` (T22)
- Source-of-truth for response shapes: https://honcho.dev/docs/v3/openapi.json
