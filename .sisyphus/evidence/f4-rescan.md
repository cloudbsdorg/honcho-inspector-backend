# F4 Scope Fidelity — Rescan after fix commit 3df68b3

Re-verified each of the 10 violations flagged by the original F4 REJECT
verdict, against fix commit `3df68b3 fix(phase-1): F4 Scope Fidelity Audit
— address 10 violations` (14 files, +731/-18).

## Per-violation verification

### V1 — T4a: HonchoConfigDirResolver.resolveOrCreate() missing → FIXED
- File: `src/main/java/com/revytechinc/honchoinspector/config/HonchoConfigDirResolver.java:74`
- `public ResolveResult resolveOrCreate()` now exists; returns a record
  carrying `(Path path, Status status)` where `Status ∈ {CREATED, EXISTS, FALLBACK}`.

### V2 — T4a: XDG_USER_ETC constant missing → FIXED
- File: `HonchoConfigDirResolver.java:24`
- `private static final Path XDG_USER_ETC = buildXdgUserEtc();` present;
  built from `${user.home}/.local/etc/honcho-inspector`.

### V3 — T4a: isRunningAsRoot() helper missing → FIXED
- File: `HonchoConfigDirResolver.java:128`
- `boolean isRunningAsRoot()` present; reads `/proc/self/status` for `Uid: 0`
  on Linux, falls back to `user.name == "root"`. Returns false on Windows / macOS.

### V4 — T4a: StartupInfoLogger not using resolveOrCreate() → FIXED
- File: `src/main/java/com/revytechinc/honchoinspector/config/StartupInfoLogger.java:70-77`
- Calls `resolver.resolveOrCreate()` and switches on status to render
  `[created]` / `[exists]` / `[fallback: <path>]` tags.

### V5 — T4a: 5 new tests missing → FIXED
- File: `src/test/java/com/revytechinc/honchoinspector/config/HonchoConfigDirResolverTest.java`
- All 5 new tests present:
  - `createDirectories_newDir_succeeds` (line 127)
  - `createDirectories_existingDir_succeeds` (line 140)
  - `createDirectories_permissionDenied_fallsBackToUserDir` (line 153)
  - `createDirectories_bothPathsFail_throws` (line 178)
  - `runningAsRoot_skipsFallback` (line 207) — uses `Assumptions.assumeTrue`
    so it skips on non-root test environments.

### V6 — T4b: SessionAuthFilter MDC enrichment missing → FIXED
- File: `src/main/java/com/revytechinc/honchoinspector/filter/SessionAuthFilter.java`
- `import org.slf4j.MDC;` (line 8)
- Constants `MDC_SESSION_ID = "session_id"`, `MDC_USER_ID = "user_id"`
  (lines 21-22)
- `MDC.put(...)` calls (lines 59-60), `MDC.remove(...)` in `finally` (lines 63-64).

### V7 — T4b: HonchoProxyService MDC enrichment missing → FIXED
- File: `src/main/java/com/revytechinc/honchoinspector/service/HonchoProxyService.java`
- `import org.slf4j.MDC;` (line 6)
- `call()` now wraps the upstream call in a `try { MDC.put(profile_id) +
  MDC.put(honcho_version); } finally { MDC.remove(...) }` block
  (lines 84-95).
- Constants `MDC_PROFILE_ID = "profile_id"`, `MDC_HONCHO_VERSION = "honcho_version"`
  (lines 97-98).

### V8 — T4b: HonchoV3Client MDC peer_id enrichment missing → FIXED
- File: `src/main/java/com/revytechinc/honchoinspector/honcho/v3/HonchoV3Client.java`
- `import org.slf4j.MDC;` (line 8)
- `call()` extracts `peerId` from `pathVars`, sets `MDC.put("peer_id", ...)`
  inside `try`, removes in `finally` (lines 266-274).
- Constant `MDC_PEER_ID = "peer_id"` (line 277).

### V9 — T4: HonchoPropertiesTest missing → FIXED
- File: `src/test/java/com/revytechinc/honchoinspector/honcho/HonchoPropertiesTest.java`
- 86 lines, 3 test methods:
  - `defaults_applyWhenEnvUnset` (asserts `apiVersion == "v3"`, `strictMode == false`)
  - `envBindsToHonchoProperties` (asserts `honcho.api-version=v4`,
    `honcho.providers.strict-mode=true`, `honcho.base-url=https://honcho.example.com`
    bind to HonchoProperties)
  - `relaxedBinding_acceptsUpperCase` (asserts `honcho.api-version=V4`
    binds case-insensitively)
- Uses `@SpringBootTest(classes = TestConfig.class)` with
  `@EnableConfigurationProperties(HonchoProperties.class)` on a nested
  static `TestConfig` to avoid pulling in the full application context.

### V10a — T26: HonchoWorkflowIntegrationTest had 10/14 tests → FIXED
- File: `src/test/java/com/revytechinc/honchoinspector/controller/HonchoWorkflowIntegrationTest.java`
- Now 14 `@Test` methods (10 original + 4 new):
  - `createPeerHappyPath` (line 192)
  - `getPeerRepresentation` (line 206)
  - `listPeerSessions` (line 222)
  - `searchMessages` (line 241)

### V10b — T26: LiveHonchoProxyIT missing workspace-id gating → FIXED
- File: `src/test/java/com/revytechinc/honchoinspector/LiveHonchoProxyIT.java:58-59`
- Both annotations present:
  - `@EnabledIfEnvironmentVariable(named = "HONCHO_LIVE_TEST", matches = "1")`
  - `@EnabledIfEnvironmentVariable(named = "HONCHO_LIVE_WORKSPACE_ID", matches = "inspector-tests")`

## Test outcome

Command: `mvn -B test -DexcludedGroups='drift,live'`

Result:
```
[WARNING] Tests run: 354, Failures: 0, Errors: 0, Skipped: 1
[INFO] BUILD SUCCESS
```

The 1 skipped test is `runningAsRoot_skipsFallback` in
`HonchoConfigDirResolverTest` — uses `Assumptions.assumeTrue(...)` to skip
on non-root CI environments. This is expected behavior, not a regression.

Total time: 47.5s.

## Operational verification

The mvn test logs confirm T4a actually fires in dev:
```
config dir primary path not writable (/etc/honcho-inspector),
  falling back to /home/mlapointe/.local/etc/honcho-inspector
honcho-inspector backend ready: ... config-dir=/home/mlapointe/.local/etc/honcho-inspector [fallback: /home/mlapointe/.local/etc/honcho-inspector] ...
```
The StartupInfoLogger renders `[fallback: <path>]` exactly as spec'd.

## Final verdict

**APPROVE.** All 10 violations resolved. Test count matches expected
baseline (354/354, 1 skipped, 0 failures, 0 errors). No regressions
detected in the diff beyond the intended fixes.
