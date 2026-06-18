# Session Handoff — Phase 1 Plan Ready

> **Read this if you're a new agent starting this session.** The full plan is at `.sisyphus/plans/phase-1-openapi-and-workflow-tests.md`. This file is a quick-start pointer.

## TL;DR for the new agent

The user has committed a **Phase 1 work plan** for the `honcho-inspector-backend` repo. Your job (when the user runs `/start-work phase-1-openapi-and-workflow-tests`) is to execute it with Sisyphus.

**Read this first if you're verifying direction:** §"Visual Architecture & Workflows" in the plan contains 6 Mermaid diagrams that show the whole shape of what we're building (deployment topology, honcho layer, wave execution, request lifecycle, version upgrade path, Phase 1 vs Phase 2 boundary). If you're not sure what a task is trying to achieve, look at the diagram.

**Do NOT:**
- Re-plan Phase 1 — the plan is approved (Momus verdict: OKAY).
- Build Phase 2 features (orgs, sharing, signup, stats, reports) — those are deferred to a separate future plan.
- Modify existing security/proxy docs — they're already committed.
- Add new endpoints to `HonchoController` — Phase 1 = current API only.

**Do:**
- Open `.sisyphus/plans/phase-1-openapi-and-workflow-tests.md` and follow the wave structure (Wave 1 → Wave 2 → Wave 3 → Wave 4 → Final Verification).
- Every task has agent profile + parallelization + QA scenarios baked in. Just dispatch and verify.
- Capture evidence in `.sisyphus/evidence/` per task.

## Critical Environment Variables

```bash
# Required for Maven (no javac in system JDK)
export JAVA_HOME=/home/mlapointe/.jdks/openjdk-25.0.2
export PATH="$JAVA_HOME/bin:$PATH"

# Live Honcho (opt-in only — recorded JSON fixture is the default)
export HONCHO_URL=https://honcho.cloudbsd.org
export HONCHO_WORKSPACE_ID=inspector-tests   # NEVER use 'default' for tests
export HONCHO_LIVE_TEST=1                    # only set when user requests live tests

# Required for production encryption (not needed in tests)
export HONCHO_CRYPTO_KEY=$(openssl rand -base64 32)
```

**Live Honcho server:** `https://mcp.honcho.cloudbsd.org/` (Bearer JWT in `~/.config/opencode/opencode.json` under `mcp.honcho`).

## Architecture (the plan revolves around this)

```
HTTP request
  → HonchoController (existing, 24 endpoints)
  → HonchoProxyService (refactored in T15)
  → HonchoClientFactory (new, version routing)
    → HonchoClient V3 / V4 (new, per-version facade with 24 methods)
    → HonchoProviderRegistry (new, auto-discovered Spring components)
      → HonchoProvider V3 (new, 8 multi-operation files)
        → Honcho v3 HTTP API
```

**Key design constraint:** users can add new endpoints or new versions WITHOUT forking. Drop a `@Component implements HonchoProvider` and it's wired in.

## What's in this repo now

| File | Status | What |
|---|---|---|
| `docs/SECURITY.md` | new | 16 findings (F-01…F-16) with severity + remediation |
| `docs/reverse-proxy.md` | new | nginx (certbot-managed), Apache, Caddy configs |
| `README.md` | modified | links new docs, expanded security/deployment sections |
| `etc/honcho-inspector/application.yml.example` | modified | prod hardening (127.0.0.1 bind, safe error includes) |
| `.sisyphus/plans/phase-1-openapi-and-workflow-tests.md` | new | **the plan** — start here |

## What's deferred to Phase 2 (do NOT build in Phase 1)

- Organizations / workspaces / multi-tenancy
- Two-tier sharing (per-org shared + per-user private profiles)
- Signup workflow (open self-register + request-join/create-new + admin approval)
- Invite/role system (admin flag exists in DB but is unread)
- Stats aggregation (Honcho v3 has no aggregate endpoints — must paginate client-side)
- Reports / export
- Rate limiting (security finding F-01)
- SSRF protection on `baseUrl` (security finding F-08)
- Generic exception scrubbing (security findings F-05/F-11)

## Verification

```bash
export JAVA_HOME=/home/mlapointe/.jdks/openjdk-25.0.2
export PATH="$JAVA_HOME/bin:$PATH"
mvn -q -DskipITs=false test    # baseline: 29/29 passing
```

## Where to ask questions

If the user asks anything Phase 1-related, open the plan and find the task. If they ask about Phase 2 features, point them to this file's "deferred" section — they need to start a new planning session.

---
*Generated at end of planning session, June 17 2026.*
