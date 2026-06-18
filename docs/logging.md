# Logging — `honcho-inspector-backend`

> **Scope:** the Spring Boot 3.5 / Java 25 backend at the current `HEAD`.
> **Audience:** operators, contributors, and incident responders.
> **Related docs:** [`docs/SECURITY.md`](SECURITY.md) §6, [`README.md`](../README.md).

This document is the source of truth for the **structured JSONL logging
policy** introduced in T4b. It defines what we log, where it lands, how it
rotates, how secrets are scrubbed, and how operators consume the output.

---

## 1. Policy

```yaml
retention_days:    30           # HONCHO_LOG_MAX_HISTORY
max_file_size_mb:  100          # HONCHO_LOG_MAX_FILE_SIZE (active file cap before mid-day roll)
total_cap_mb:      500          # HONCHO_LOG_TOTAL_SIZE_CAP (across active + rotated files)
format:            jsonl        # JSON Lines, one event per line
rotation:          daily + size-trigger
compression:       gzip         # .jsonl.gz
location:          $HONCHO_CONFIG_DIR/logs/
scrub_fields:      [apiKey, Bearer, password]
```

**Defaults are conservative.** 30 days × 3 MB/day is well under the 500 MB
cap; operators with heavier traffic should lower `retention_days` or raise
`total_cap_mb`, not lower `max_file_size_mb` (smaller files produce more
compression overhead and more index entries on the ingest side).

### 1.1 Sizing math

At 3 MB/day:

- 100 MB file cap × 30 days = up to **3 GB** worst case before rotation,
  capped at **500 MB total** by `total-size-cap`. The cap kicks in
  *before* the daily count is reached, so old files are evicted first.
- 500 MB / 3 MB/day ≈ **167 days**, but the practical floor is `max_history`
  × `max_file_size` (30 × 100 MB = 3 GB worst case, but `total_size_cap`
  trims to 500 MB).
- Realistic steady-state for a small-team admin surface: **6 months at
  ~3 MB/day**, comfortably inside the 500 MB cap.

If you see "rolled file pruned by totalSizeCap" warnings more than once a
week, your traffic has outgrown the defaults — either raise
`HONCHO_LOG_TOTAL_SIZE_CAP` or shorten `HONCHO_LOG_MAX_HISTORY`. Do not
**lower** `HONCHO_LOG_MAX_FILE_SIZE` below 10 MB; it produces too many small
rotated files for downstream ingest pipelines to handle efficiently.

### 1.2 Compliance (EU/GDPR)

`session_id` and `user_id` are present in every authenticated request's log
events because they are the **only** stable correlation key we have for
debugging and incident response. They are PII under GDPR (a session id can
be linked to a user row; a user id maps directly to an account). The
retention justification is **legitimate interest in service operation,
debugging, and security incident response** (Art. 6(1)(f)).

Operators in regulated environments should:

- Set `HONCHO_LOG_MAX_HISTORY` lower than the default (e.g. **7** or
  **14** days) to shorten the PII exposure window.
- Ship JSONL to a SIEM with its own retention controls rather than relying
  solely on on-disk rotation.
- Consider scrubbing `user_id` and `session_id` at ingest time if your
  pipeline can correlate by `request_id` alone (the request id is
  96-bit SecureRandom hex and is not PII).

We do **not** log passwords, plaintext API keys, or plaintext Bearer
tokens (see §3). We **do** log the fact that an authentication event
happened, with `user_id` and the source IP via MDC.

---

## 2. Event schema

Every line in `$HONCHO_CONFIG_DIR/logs/honcho-inspector.jsonl` is a single
JSON object (one event per line, JSONL / NDJSON). The schema is fixed; do
not extend it without updating this document.

### 2.1 Fixed fields

| Field          | Type   | Source                                  | Example                                       |
| -------------- | ------ | --------------------------------------- | --------------------------------------------- |
| `@timestamp`   | string | Logback `timestampPattern` (UTC, ms)    | `"2026-06-17T22:34:56.789Z"`                  |
| `@version`     | string | LogstashEncoder default                 | `"1"`                                         |
| `level`        | string | SLF4J level                             | `"INFO"`, `"WARN"`, `"ERROR"`, `"DEBUG"`      |
| `level_value`  | int    | Logback level numeric                   | `20000`                                       |
| `logger_name`  | string | SLF4J logger name                       | `"com.revytechinc.honchoinspector.controller.HonchoController"` |
| `thread_name`  | string | emitting thread                         | `"main"`, `"http-nio-8080-exec-3"`            |
| `message`      | string | the rendered SLF4J message              | `"profile created id=42 label=prod"`          |
| `stack_trace`  | string | present on exceptions only              | `"java.lang.IllegalStateException: ..."`      |
| `service`      | string | encoder `customFields` constant         | `"honcho-inspector-backend"`                  |
| `version`      | string | encoder `customFields` from Maven build | `"0.1.0-SNAPSHOT"`                            |

### 2.2 MDC fields (when set)

These appear **only** when set by the code path that emits the event. They
are populated in a `try` block and cleared in `finally` to prevent leakage
between requests (Spring reuses filter threads).

| MDC field       | Set by                          | Scope                          | PII? |
| --------------- | ------------------------------- | ------------------------------ | ---- |
| `request_id`    | `SessionAuthFilter`             | every authenticated request    | No (96-bit SecureRandom hex, not user-linked) |
| `user_id`       | `SessionAuthFilter`             | every authenticated request    | **Yes** (maps to a `users` row) |
| `session_id`    | `SessionAuthFilter`             | every authenticated request    | **Yes** (192-bit session token) |
| `honcho_version`| `HonchoProxyService.withCallMdc` | every outbound Honcho call    | No |
| `peer_id`       | `HonchoProxyService.withCallMdc` | outbound calls under `/peers/...` | Indirect (Honcho-side identity, not a user row) |

### 2.3 Example event

```json
{
  "@timestamp":   "2026-06-17T22:34:56.789Z",
  "@version":     "1",
  "level":        "INFO",
  "level_value":  20000,
  "logger_name":  "com.revytechinc.honchoinspector.service.HonchoProxyService",
  "thread_name":  "http-nio-8080-exec-3",
  "message":      "GET /v3/workspaces/ws-1/peers/p-7 -> 200",
  "service":      "honcho-inspector-backend",
  "version":      "0.1.0-SNAPSHOT",
  "request_id":   "a3b8c1d4e5f6a7b8c9d0e1f2",
  "user_id":      "42",
  "session_id":   "9c1e2a0c4f2b4a3b9d8c7e6f5a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0d9e8f7a6b5",
  "honcho_version": "v3",
  "peer_id":      "p-7"
}
```

---

## 3. Secret scrubbing

A `MaskingJsonGeneratorDecorator` runs on the Jackson output stage of the
Logstash encoder. It applies these regexes (Logback core eats standalone
`}` in `<value>` content, so the character classes use `[^"\s,]+` instead
of `[^"\s,}]+` — see [§6.1](#61-known-gotcha-the--stripping-bug)):

| Pattern                                          | Captures           | Replacement                |
| ------------------------------------------------ | ------------------ | -------------------------- |
| `(?i)(Bearer\s+)[A-Za-z0-9._\-]+`                | `Bearer <token>`   | `Bearer ***`               |
| `(?i)("?api_?key"?\s*[:=]\s*"?)([^"\s,]+)`       | `apiKey=<value>`   | `apiKey=***`               |
| `(?i)("?password"?\s*[:=]\s*"?)([^"\s,]+)`       | `password=<value>` | `password=***`             |
| `(?i)("?pass"?\s*[:=]\s*"?)([^"\s,]+)`          | `pass=<value>`     | `pass=***`                 |

A secret that **does not** match one of these patterns is **not**
scrubbed. If you need to log a new secret type, add a pattern — do not
rely on the encoder to infer it.

The decorator works at the Jackson level, so it catches secrets embedded
in **any** field — message, stack trace, MDC, structured arguments — not
just a fixed field name.

---

## 4. Operational examples

### 4.1 Tail live JSONL (compact view)

```bash
tail -F "$HONCHO_CONFIG_DIR/logs/honcho-inspector.jsonl" \
  | jq -c '{ts:.@timestamp, level, msg:.message, logger:.logger_name}'
```

### 4.2 Filter by level (ERRORs only, across today + yesterday's gz files)

```bash
jq -c 'select(.level=="ERROR")' \
  "$HONCHO_CONFIG_DIR/logs/honcho-inspector."*.jsonl.gz \
  | gunzip
```

(`jq` reads `.gz` files transparently when given a filename pattern; the
`| gunzip` is redundant when reading directly but kept for piping into
other tools.)

### 4.3 Find all requests for one user

```bash
jq -c 'select(.user_id=="42")' \
  "$HONCHO_CONFIG_DIR/logs/honcho-inspector."*.jsonl.gz
```

### 4.4 Find all upstream Honcho errors

```bash
jq -c 'select(.logger_name|test("Honcho")) | select(.level=="ERROR")' \
  "$HONCHO_CONFIG_DIR/logs/honcho-inspector."*.jsonl.gz
```

### 4.5 Count events per minute (rough)

```bash
jq -r '.["@timestamp"][:16]' "$HONCHO_CONFIG_DIR/logs/honcho-inspector.jsonl" \
  | uniq -c
```

### 4.6 Verify a secret was scrubbed

```bash
# Should print nothing (exit 1):
grep -F 'secret-123' "$HONCHO_CONFIG_DIR/logs/honcho-inspector.jsonl"

# Should print at least one line (exit 0):
grep -c '"\*\*\*"' "$HONCHO_CONFIG_DIR/logs/honcho-inspector.jsonl"
```

---

## 5. Configuration knobs (env vars)

All four are read by `logback-spring.xml` and surfaced via
`HonchoProperties.log.*` for programmatic access.

| Env var                       | Default  | Bound to                           |
| ----------------------------- | -------- | ---------------------------------- |
| `HONCHO_LOG_LEVEL`            | `INFO`   | `honcho.log.level`                 |
| `HONCHO_LOG_MAX_FILE_SIZE`    | `100MB`  | `honcho.log.max-file-size`         |
| `HONCHO_LOG_MAX_HISTORY`      | `30`     | `honcho.log.max-history`           |
| `HONCHO_LOG_TOTAL_SIZE_CAP`   | `500MB`  | `honcho.log.total-size-cap`        |

The `application.yml` block is the source of truth for the defaults; the
env vars override it.

---

## 6. Implementation notes

### 6.1 Known gotcha — the `}` stripping bug

Logback core's `ch.qos.logback.core.subst.Tokenizer` drops the literal
`}` character from `<value>` and `<mask>` content inside
`<jsonGeneratorDecorator>` value-mask elements (see
[qos-ch/logback#836](https://github.com/qos-ch/logback/issues/836) and
[LOGBACK-1461](https://jira.qos.ch/browse/LOGBACK-1461)). CDATA wrapping
**alone does not fix this** — `NodeToStringTransformer` strips `}` after
SAX parsing. The workaround used here is twofold:

1. Wrap every regex `<value>` in `<![CDATA[...]]>` (defensive, in case
   Logback fixes the upstream bug).
2. Remove `}` from the character class, using `[^"\s,]+` instead of
   `[^"\s,}]+`. The `}` was defensive — JSON object boundaries rarely
   appear inside the value being masked, and the `+` is bounded by `"`,
   whitespace, and `,` which dominate.

This trade-off was confirmed by Phil Webb (logstash-logback-encoder
maintainer) in [logfellow/logstash-logback-encoder#1009](https://github.com/logfellow/logstash-logback-encoder/discussions/1009).
**Do not add `}` back to any test or config regex** without verifying the
test suite still passes end-to-end.

### 6.2 MDC hygiene

`SessionAuthFilter` and `HonchoProxyService` both use the
`MDC.put(...) → try → finally MDC.remove(...)` pattern. Spring reuses
filter threads via the default `TaskExecutor`, so a missing `MDC.remove`
would leak `user_id` and `session_id` into the next request handled by
the same thread. Always pair `MDC.put` with `MDC.remove` in a `finally`
block — see `filter/SessionAuthFilter.java` and
`service/HonchoProxyService.java` for the canonical pattern.

### 6.3 Rotation semantics

`SizeAndTimeBasedRollingPolicy` rolls the active file when **either**:

- the active file size exceeds `maxFileSize` (mid-day roll, `%i` index
  disambiguates same-day rolls), **or**
- the date advances past midnight UTC.

Old files are gzipped and pruned in age order when the total of all
files (active + rotated) exceeds `totalSizeCap`. `maxHistory` is a
secondary cap (by age) applied during the same cleanup.

The compression mode is `gz`, which produces the `.gz` suffix; we
preserve `.jsonl` before it so rotated files are `.jsonl.gz` and tooling
that filters by suffix (e.g. `*.jsonl.gz`) keeps working.

---

## 7. Tests

The following tests live in
`src/test/java/com/revytechinc/honchoinspector/logging/`:

- `LogbackConfigTest` — load `logback-spring.xml`, assert both `FILE_JSONL`
  and `CONSOLE_JSONL` appenders exist, each with a `LogstashEncoder`.
- `JsonlFormatTest` — log a few SLF4J messages into a temp
  `HONCHO_CONFIG_DIR`, read the produced JSONL, assert every line is
  valid JSON with `@timestamp`, `level`, `message`, `logger_name`,
  `service`, `version`.
- `LogRotationTest` — set `HONCHO_LOG_MAX_FILE_SIZE=1KB` and
  `HONCHO_LOG_MAX_HISTORY=2`, log 10,000 short messages, assert ≥1
  rotated file exists, total file count ≤ `maxHistory + 1` active, and
  rotated files end in `.jsonl.gz`.
- `LogScrubbingTest` — log a message containing `apiKey=secret-123` and
  `Bearer abc.def.ghi`, assert neither secret appears in the JSONL
  (both replaced with `***`).
- `LogMdcTest` — set MDC fields `session_id` and `user_id`, log a
  message, clear MDC, log again. Assert the first line has the MDC
  fields and the second does not.
