#!/usr/bin/env bash
#
# Public CLI script: capture JSON response fixtures from a Honcho v3 instance
# and write them to src/test/resources/fixtures/honcho/v3/. Used by Phase-1
# test infrastructure to populate Mock Honcho fixtures (T23) for downstream
# workflow tests (T24-T26).
#
# Two modes:
#   1. REAL (default): tries to call a real Honcho instance via curl. Requires
#      HONCHO_API_KEY (or reads from ~/.config/opencode/opencode.json).
#      If the real Honcho is reachable AND the auth header is accepted AND the
#      workspace exists, the actual response bodies are saved.
#      If any precondition fails, the script falls back to synthetic mode and
#      the resulting fixtures are tagged with `"synthetic": true`.
#
#   2. SYNTHETIC (HONCHO_SYNTHETIC=1): skips all HTTP calls. Generates
#      representative fixture JSON whose shapes are derived from the official
#      Honcho v3 OpenAPI spec (https://honcho.dev/docs/v3/openapi.json).
#      Useful for CI where a live Honcho is not available.
#
# Idempotent: re-running overwrites existing fixtures in the destination dir.
# In real mode, the script does NOT delete the workspace or peer it created;
# the caller is responsible for cleanup on the real Honcho if desired.
#
# Usage:
#   ./scripts/capture-honcho-fixtures.sh                       # try real
#   HONCHO_SYNTHETIC=1 ./scripts/capture-honcho-fixtures.sh    # force synthetic
#   HONCHO_URL=https://api.honcho.dev \
#     HONCHO_API_KEY=hk-... \
#     WORKSPACE_ID=myws \
#     ./scripts/capture-honcho-fixtures.sh                     # override defaults
#
# Environment:
#   HONCHO_URL        Default: https://honcho.cloudbsd.org
#   HONCHO_API_KEY    Required for real mode (or read from opencode.json)
#   HONCHO_USER_NAME  Default: mlapointe (or read from opencode.json)
#   WORKSPACE_ID      Default: default
#   HONCHO_SYNTHETIC  If set to "1", skip real-Honcho probing
#   HONCHO_VERSION    Default: 3.0.7 (Honcho v3 OpenAPI version)
#   FIXTURE_DIR       Default: src/test/resources/fixtures/honcho/v3
#
# Output:
#   $FIXTURE_DIR/*.json — one file per Honcho v3 operation. Each file:
#     {
#       "_meta": {
#         "captured_at": "2026-06-18T...",
#         "honcho_version": "3.0.7",
#         "endpoint": "POST /v3/workspaces/{ws}/peers",
#         "method": "POST",
#         "synthetic": false,
#         "synthetic_reason": ""           # only set when synthetic=true
#       },
#       "data": { ... actual response ... }
#     }

set -euo pipefail

HONCHO_URL="${HONCHO_URL:-https://honcho.cloudbsd.org}"
HONCHO_USER_NAME="${HONCHO_USER_NAME:-}"
HONCHO_API_KEY="${HONCHO_API_KEY:-}"
HONCHO_SYNTHETIC="${HONCHO_SYNTHETIC:-}"
HONCHO_VERSION="${HONCHO_VERSION:-3.0.7}"
WORKSPACE_ID="${WORKSPACE_ID:-default}"
FIXTURE_DIR="${FIXTURE_DIR:-src/test/resources/fixtures/honcho/v3}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OPENCODE_CONFIG="${HOME}/.config/opencode/opencode.json"

EPOCH=$(date +%s)
PEER_NAME="fixture-capture-${EPOCH}"
SESSION_NAME="fixture-session-${EPOCH}"
MESSAGE_CONTENT="hello from fixture-capture ${EPOCH}"
CAPTURED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
SYNTHETIC=false
SYNTHETIC_REASON=""

if [[ -z "$HONCHO_API_KEY" && -f "$OPENCODE_CONFIG" ]] && command -v jq >/dev/null 2>&1; then
  HONCHO_API_KEY=$(jq -r '.mcp.honcho.headers.Authorization // ""' "$OPENCODE_CONFIG" 2>/dev/null \
    | sed -E 's/^Bearer //')
  [[ -z "$HONCHO_USER_NAME" ]] && HONCHO_USER_NAME=$(jq -r '.mcp.honcho.headers["X-Honcho-User-Name"] // ""' "$OPENCODE_CONFIG" 2>/dev/null)
fi
HONCHO_USER_NAME="${HONCHO_USER_NAME:-mlapointe}"

if [[ "$HONCHO_SYNTHETIC" == "1" || "$HONCHO_SYNTHETIC" == "true" ]]; then
  SYNTHETIC=true
  SYNTHETIC_REASON="HONCHO_SYNTHETIC=1 set in environment"
fi

mkdir -p "$FIXTURE_DIR"

log() { printf '[capture] %s\n' "$*" >&2; }
fail() { printf '[capture] ERROR: %s\n' "$*" >&2; exit 1; }

sanitize_json() {
  local body="$1"
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$body" \
      | jq 'walk(if type == "string" then gsub("Bearer [A-Za-z0-9._-]+"; "Bearer <REDACTED>") else . end)' \
      2>/dev/null \
      || printf '%s' "$body"
  else
    printf '%s' "$body" | sed -E 's/Bearer [A-Za-z0-9._-]+/Bearer <REDACTED>/g'
  fi
}

write_fixture() {
  local name="$1"
  local endpoint="$2"
  local method="$3"
  local data="$4"
  local syn="${5:-false}"
  local syn_reason="${6:-}"
  local path="$FIXTURE_DIR/$name.json"

  local meta
  if [[ "$syn" == "true" ]]; then
    meta=$(jq -n \
      --arg ts "$CAPTURED_AT" \
      --arg v  "$HONCHO_VERSION" \
      --arg e  "$endpoint" \
      --arg m  "$method" \
      --arg r  "$syn_reason" \
      '{captured_at:$ts, honcho_version:$v, endpoint:$e, method:$m, synthetic:true, synthetic_reason:$r}')
  else
    meta=$(jq -n \
      --arg ts "$CAPTURED_AT" \
      --arg v  "$HONCHO_VERSION" \
      --arg e  "$endpoint" \
      --arg m  "$method" \
      '{captured_at:$ts, honcho_version:$v, endpoint:$e, method:$m, synthetic:false}')
  fi

  if command -v jq >/dev/null 2>&1; then
    jq -n --argjson meta "$meta" --argjson data "$data" '{_meta:$meta, data:$data}' > "$path"
  else
    printf '%s\n' "{\"_meta\":$meta,\"data\":$data}" > "$path"
  fi

  log "  wrote $name.json ($(wc -c < "$path") bytes)"
}

write_synthetic() {
  local name="$1"
  local endpoint="$2"
  local method="$3"
  local syn_reason="$4"

  HONCHO_VERSION="$HONCHO_VERSION" \
  CAPTURED_AT="$CAPTURED_AT" \
  NAME="$name" \
  ENDPOINT="$endpoint" \
  METHOD="$method" \
  SYN_REASON="$syn_reason" \
  FIXTURE_DIR="$FIXTURE_DIR" \
  python3 - <<'PYEOF'
import json, os, pathlib

name       = os.environ["NAME"]
endpoint   = os.environ["ENDPOINT"]
method     = os.environ["METHOD"]
syn_reason = os.environ["SYN_REASON"]
captured   = os.environ["CAPTURED_AT"]
version    = os.environ["HONCHO_VERSION"]
out_dir    = pathlib.Path(os.environ["FIXTURE_DIR"])
out_dir.mkdir(parents=True, exist_ok=True)

WS    = "fixture-ws"
PEER  = "fixture-peer-001"
PEER2 = "fixture-peer-002"
SESS  = "fixture-session-001"
MSG1  = "fixture-msg-001"
MSG2  = "fixture-msg-002"
CONCL = "fixture-conclusion-001"
NOW   = captured

def page(items, page=1, size=50):
    n = len(items)
    return {"items": items, "total": n, "page": page, "size": size,
            "pages": max(1, (n + size - 1) // size)}

def peer(pid=PEER):
    return {"id": pid, "workspace_id": WS, "created_at": NOW,
            "metadata": {"source": "fixture-capture"}, "configuration": {}}

def session(sid=SESS):
    return {"id": sid, "is_active": True, "workspace_id": WS, "created_at": NOW,
            "metadata": {"source": "fixture-capture"}, "configuration": {}}

def message(mid=MSG1, content="hello from fixture-capture", peer_id=PEER,
            session_id=SESS, token_count=8):
    return {"id": mid, "content": content, "peer_id": peer_id,
            "session_id": session_id, "workspace_id": WS,
            "metadata": {"source": "fixture"}, "created_at": NOW,
            "token_count": token_count}

def conclusion(cid=CONCL, content="User prefers concise answers",
               observer_id=PEER, observed_id=PEER2, session_id=SESS):
    return {"id": cid, "content": content, "observer_id": observer_id,
            "observed_id": observed_id, "session_id": session_id,
            "created_at": NOW}

DATA = {
    "list-peers":          page([peer(PEER), peer(PEER2)]),
    "create-peer":         peer(PEER),
    "get-peer-card":       {"peer_card": ["Prefers concise answers",
                                          "Working on Phase 1 tests"]},
    "update-peer-card":    {"peer_card": ["Prefers concise answers",
                                          "Updated by fixture-capture"]},
    "get-peer-representation":
        {"representation": "User is a backend engineer working on "
                           "honcho-inspector.\nRecent focus: Phase 1 "
                           "OpenAPI + workflow tests."},
    "list-peer-conclusions":
        page([conclusion(),
              conclusion("fixture-conclusion-002", "User prefers dark mode",
                         CONCL, PEER, SESS)]),
    "list-peer-sessions":
        page([session(), session("fixture-session-002")]),
    "query-peer-conclusions": page([conclusion()]),
    "list-sessions":
        page([session(), session("fixture-session-002")]),
    "create-session":      session(),
    "get-session":         session(),
    "get-session-peers":   [peer(PEER)],
    "add-peer-to-session": {"status": "added", "peer_id": PEER, "session_id": SESS},
    "get-session-context": {
        "id": SESS,
        "messages": [message(MSG1, "hello world"),
                     message(MSG2, "fixture second message", PEER, SESS, 4)],
        "summary": None, "peer_representation": None, "peer_card": None,
    },
    "get-session-summaries": {"id": SESS, "short_summary": None, "long_summary": None},
    "add-message":         {"messages": [message(MSG1, "hello world")]},
    "list-session-messages":
        page([message(MSG1, "hello world"),
              message(MSG2, "fixture second message", PEER, SESS, 4)]),
    "get-session-message": message(MSG1, "hello world"),
    "search-session-messages": page([message(MSG1, "hello world")]),
    "workspace-info":      {"id": WS, "metadata": {"source": "fixture-capture"},
                            "configuration": {}, "created_at": NOW},
    "queue-status":        {"total_work_units": 0, "completed_work_units": 0,
                            "in_progress_work_units": 0, "pending_work_units": 0,
                            "sessions": None},
    "search-messages":     page([message(MSG1, "hello world")]),
    "peer-chat":           {"content": "Hello, fixture-capture peer. "
                                        "How can I help you today?"},
    "peer-search":         page([message(MSG1, "hello world")]),
    "schedule-dream":      {"status": "scheduled", "dream_type": "omni",
                            "observer": PEER},
    "search":              page([message(MSG1, "hello world")]),
    "list-peers-post":     page([peer(PEER), peer(PEER2)]),
    "list-sessions-post":  page([session(), session("fixture-session-002")]),
    "list-conclusions":
        page([conclusion(),
              conclusion("fixture-conclusion-002", "User prefers dark mode",
                         CONCL, PEER, SESS)]),
    "representation":
        {"representation": "User is a backend engineer working on "
                           "honcho-inspector.\nRecent focus: Phase 1 "
                           "OpenAPI + workflow tests."},
}

data = DATA.get(name, {})

meta = {"captured_at": captured, "honcho_version": version,
        "endpoint": endpoint, "method": method,
        "synthetic": True, "synthetic_reason": syn_reason}
out = {"_meta": meta, "data": data}
out_path = out_dir / f"{name}.json"
out_path.write_text(json.dumps(out, indent=2) + "\n")
print(f"  wrote {name}.json ({out_path.stat().st_size} bytes)")
PYEOF
}

real_call() {
  local name="$1"
  local method="$2"
  local path="$3"
  local body="${4:-}"
  local endpoint_label="$5"

  local args=(-sS --max-time 15 -X "$method"
    -H "Authorization: Bearer $HONCHO_API_KEY"
    -H "X-Honcho-User-Name: $HONCHO_USER_NAME"
    -H "Accept: application/json"
    -w '\n__HTTP_STATUS__:%{http_code}')

  if [[ -n "$body" ]]; then
    args+=(-H "Content-Type: application/json" --data-raw "$body")
  fi

  local resp http_status payload
  if ! resp=$(curl "${args[@]}" "${HONCHO_URL}/${path}" 2>&1); then
    log "  real call to $endpoint_label failed (transport); using synthetic"
    return 1
  fi

  http_status=$(printf '%s' "$resp" | grep -oE '__HTTP_STATUS__:[0-9]+' | tail -1 | cut -d: -f2)
  payload=$(printf '%s' "$resp" | sed 's/__HTTP_STATUS__:[0-9]*$//')

  if [[ "$http_status" =~ ^2 ]]; then
    payload=$(sanitize_json "$payload")
    write_fixture "$name" "$endpoint_label" "$method" "$payload" "false"
    return 0
  fi
  log "  real $endpoint_label returned HTTP $http_status; using synthetic"
  return 1
}

probe_real_honcho() {
  if [[ "$SYNTHETIC" == "true" ]]; then
    return 1
  fi
  if [[ -z "$HONCHO_API_KEY" ]]; then
    log "no HONCHO_API_KEY available; switching to synthetic"
    SYNTHETIC=true
    SYNTHETIC_REASON="no HONCHO_API_KEY (and no ~/.config/opencode/opencode.json with .mcp.honcho.headers.Authorization)"
    return 1
  fi
  local code
  code=$(curl -sS --max-time 5 -o /dev/null -w "%{http_code}" \
    -H "Authorization: Bearer $HONCHO_API_KEY" \
    -H "X-Honcho-User-Name: $HONCHO_USER_NAME" \
    "${HONCHO_URL}/v3/workspaces/${WORKSPACE_ID}" 2>/dev/null || echo "000")
  if [[ ! "$code" =~ ^(200|201|404|405|401|403)$ ]]; then
    log "real Honcho probe returned HTTP $code; switching to synthetic"
    SYNTHETIC=true
    SYNTHETIC_REASON="real Honcho probe returned HTTP $code (not reachable)"
    return 1
  fi
  if [[ "$code" =~ ^(401|403)$ ]]; then
    log "real Honcho returned HTTP $code (auth/perm); switching to synthetic"
    SYNTHETIC=true
    SYNTHETIC_REASON="real Honcho returned HTTP $code (auth or permission denied)"
    return 1
  fi
  return 0
}

log "Honcho URL:    $HONCHO_URL"
log "Workspace ID:  $WORKSPACE_ID"
log "User name:     $HONCHO_USER_NAME"
log "Fixture dir:   $FIXTURE_DIR"
log "Honcho ver:    $HONCHO_VERSION"

if probe_real_honcho; then
  log "mode: REAL (Honcho is reachable)"
else
  log "mode: SYNTHETIC (reason: $SYNTHETIC_REASON)"
fi

WS="$WORKSPACE_ID"

if [[ "$SYNTHETIC" == "true" ]]; then
  write_synthetic "create-peer" "POST /v3/workspaces/{ws}/peers" "POST" "$SYNTHETIC_REASON"
else
  BODY="{\"id\":\"${PEER_NAME}\",\"metadata\":{\"source\":\"fixture-capture\"},\"configuration\":{}}"
  if ! real_call "create-peer" "POST" "v3/workspaces/${WS}/peers" "$BODY" \
      "POST /v3/workspaces/{ws}/peers"; then
    SYNTHETIC=true
    SYNTHETIC_REASON="create-peer failed (likely auth/permission); falling back to synthetic"
    write_synthetic "create-peer" "POST /v3/workspaces/{ws}/peers" "POST" "$SYNTHETIC_REASON"
  fi
fi

if [[ "$SYNTHETIC" == "true" ]]; then
  write_synthetic "create-session" "POST /v3/workspaces/{ws}/sessions" "POST" "$SYNTHETIC_REASON"
else
  BODY="{\"id\":\"${SESSION_NAME}\",\"metadata\":{\"source\":\"fixture-capture\"},\"peers\":{\"${PEER_NAME}\":{}},\"configuration\":{}}"
  if ! real_call "create-session" "POST" "v3/workspaces/${WS}/sessions" "$BODY" \
      "POST /v3/workspaces/{ws}/sessions"; then
    SYNTHETIC=true
    SYNTHETIC_REASON="create-session failed; falling back to synthetic"
    write_synthetic "create-session" "POST /v3/workspaces/{ws}/sessions" "POST" "$SYNTHETIC_REASON"
  fi
fi

if [[ "$SYNTHETIC" == "true" ]]; then
  write_synthetic "add-message" "POST /v3/workspaces/{ws}/sessions/{sessionId}/messages" "POST" "$SYNTHETIC_REASON"
else
  BODY="{\"messages\":[{\"content\":\"${MESSAGE_CONTENT}\",\"peer_id\":\"${PEER_NAME}\",\"metadata\":{\"source\":\"fixture\"}}]}"
  if ! real_call "add-message" "POST" "v3/workspaces/${WS}/sessions/${SESSION_NAME}/messages" "$BODY" \
      "POST /v3/workspaces/{ws}/sessions/{sessionId}/messages"; then
    SYNTHETIC=true
    SYNTHETIC_REASON="add-message failed; falling back to synthetic"
    write_synthetic "add-message" "POST /v3/workspaces/{ws}/sessions/{sessionId}/messages" "POST" "$SYNTHETIC_REASON"
  fi
fi

declare -a GET_OPS=(
  "list-peers|POST|v3/workspaces/${WS}/peers/list|POST /v3/workspaces/{ws}/peers/list"
  "get-peer-card|GET|v3/workspaces/${WS}/peers/${PEER_NAME}/card|GET /v3/workspaces/{ws}/peers/{peerId}/card"
  "get-peer-representation|GET|v3/workspaces/${WS}/peers/${PEER_NAME}/representation|GET /v3/workspaces/{ws}/peers/{peerId}/representation"
  "list-peer-conclusions|GET|v3/workspaces/${WS}/peers/${PEER_NAME}/conclusions|GET /v3/workspaces/{ws}/peers/{peerId}/conclusions"
  "list-peer-sessions|GET|v3/workspaces/${WS}/peers/${PEER_NAME}/sessions|GET /v3/workspaces/{ws}/peers/{peerId}/sessions"
  "list-sessions|POST|v3/workspaces/${WS}/sessions|POST /v3/workspaces/{ws}/sessions"
  "get-session|GET|v3/workspaces/${WS}/sessions/${SESSION_NAME}|GET /v3/workspaces/{ws}/sessions/{sessionId}"
  "get-session-peers|GET|v3/workspaces/${WS}/sessions/${SESSION_NAME}/peers|GET /v3/workspaces/{ws}/sessions/{sessionId}/peers"
  "get-session-context|GET|v3/workspaces/${WS}/sessions/${SESSION_NAME}/context|GET /v3/workspaces/{ws}/sessions/{sessionId}/context"
  "list-session-messages|GET|v3/workspaces/${WS}/sessions/${SESSION_NAME}/messages|GET /v3/workspaces/{ws}/sessions/{sessionId}/messages"
  "get-session-summaries|GET|v3/workspaces/${WS}/sessions/${SESSION_NAME}/summaries|GET /v3/workspaces/{ws}/sessions/{sessionId}/summaries"
  "queue-status|GET|v3/workspaces/${WS}/queue-status|GET /v3/workspaces/{ws}/queue-status"
  "workspace-info|GET|v3/workspaces/${WS}|GET /v3/workspaces/{ws}"
  "representation|GET|v3/workspaces/${WS}/peers/${PEER_NAME}/representation|GET /v3/workspaces/{ws}/peers/{peerId}/representation"
  "list-conclusions|GET|v3/workspaces/${WS}/conclusions|GET /v3/workspaces/{ws}/conclusions"
)
for entry in "${GET_OPS[@]}"; do
  IFS='|' read -r name method path endpoint <<<"$entry"
  if [[ "$SYNTHETIC" == "true" ]]; then
    write_synthetic "$name" "$endpoint" "$method" "$SYNTHETIC_REASON"
  else
    if ! real_call "$name" "$method" "$path" "" "$endpoint"; then
      write_synthetic "$name" "$endpoint" "$method" "real call failed"
    fi
  fi
done

declare -a POST_OPS=(
  "search-messages|POST|v3/workspaces/${WS}/search|POST /v3/workspaces/{ws}/search|{\"query\":\"fixture\",\"limit\":10}"
  "schedule-dream|POST|v3/workspaces/${WS}/peers/${PEER_NAME}/dreams|POST /v3/workspaces/{ws}/peers/{peerId}/dreams|{\"observer\":\"${PEER_NAME}\",\"dream_type\":\"omni\"}"
  "peer-chat|POST|v3/workspaces/${WS}/peers/${PEER_NAME}/chat|POST /v3/workspaces/{ws}/peers/{peerId}/chat|{\"query\":\"hello from fixture-capture\"}"
  "peer-search|POST|v3/workspaces/${WS}/peers/${PEER_NAME}/search|POST /v3/workspaces/{ws}/peers/{peerId}/search|{\"query\":\"hello\",\"limit\":10}"
  "query-peer-conclusions|POST|v3/workspaces/${WS}/peers/${PEER_NAME}/conclusions/query|POST /v3/workspaces/{ws}/peers/{peerId}/conclusions/query|{\"query\":\"hello\",\"top_k\":5}"
  "search-session-messages|POST|v3/workspaces/${WS}/sessions/${SESSION_NAME}/search|POST /v3/workspaces/{ws}/sessions/{sessionId}/search|{\"query\":\"hello\",\"limit\":10}"
  "list-peers-post|POST|v3/workspaces/${WS}/peers/list|POST /v3/workspaces/{ws}/peers/list|{}"
  "list-sessions-post|POST|v3/workspaces/${WS}/sessions|POST /v3/workspaces/{ws}/sessions|{}"
  "add-peer-to-session|POST|v3/workspaces/${WS}/sessions/${SESSION_NAME}/peers|POST /v3/workspaces/{ws}/sessions/{sessionId}/peers|{\"peer_id\":\"${PEER_NAME}\"}"
  "get-session-message|GET|v3/workspaces/${WS}/sessions/${SESSION_NAME}/messages/placeholder|GET /v3/workspaces/{ws}/sessions/{sessionId}/messages/{messageId}|"
  "update-peer-card|POST|v3/workspaces/${WS}/peers/${PEER_NAME}/card|POST /v3/workspaces/{ws}/peers/{peerId}/card|{\"peer_card\":[\"Updated by fixture-capture\"]}"
)
for entry in "${POST_OPS[@]}"; do
  IFS='|' read -r name method path endpoint body <<<"$entry"
  if [[ "$SYNTHETIC" == "true" ]]; then
    write_synthetic "$name" "$endpoint" "$method" "$SYNTHETIC_REASON"
  else
    if ! real_call "$name" "$method" "$path" "$body" "$endpoint"; then
      write_synthetic "$name" "$endpoint" "$method" "real call failed"
    fi
  fi
done

if [[ "$SYNTHETIC" != "true" ]]; then
  log "tearing down created peer/session on real Honcho (best effort)"
  curl -sS --max-time 5 -X DELETE \
    -H "Authorization: Bearer $HONCHO_API_KEY" \
    -H "X-Honcho-User-Name: $HONCHO_USER_NAME" \
    "${HONCHO_URL}/v3/workspaces/${WS}/sessions/${SESSION_NAME}" >/dev/null 2>&1 || true
  curl -sS --max-time 5 -X DELETE \
    -H "Authorization: Bearer $HONCHO_API_KEY" \
    -H "X-Honcho-User-Name: $HONCHO_USER_NAME" \
    "${HONCHO_URL}/v3/workspaces/${WS}/peers/${PEER_NAME}" >/dev/null 2>&1 || true
fi

FIXTURE_COUNT=$(find "$FIXTURE_DIR" -maxdepth 1 -name '*.json' -type f | wc -l | tr -d ' ')
log "done. Wrote $FIXTURE_COUNT fixture files to $FIXTURE_DIR"

if grep -rE 'Bearer [A-Za-z0-9._-]{20,}' "$FIXTURE_DIR" 2>/dev/null; then
  fail "fixture files contain a Bearer token with >20 chars; sanitizer missed something"
fi

if (( FIXTURE_COUNT < 15 )); then
  fail "wrote only $FIXTURE_COUNT fixtures; expected ≥15"
fi

log "OK: $FIXTURE_COUNT fixtures, no leaked bearer tokens"
