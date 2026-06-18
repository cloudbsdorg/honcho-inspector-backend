# Decisions — Phase 1 Plan

Architectural choices during execution.

## Wave 1 task ordering (with T4a, T4b, T17/T18 alignment)

The plan has 36 implementation tasks (T1–T8, T4a, T4b, T9–T34). All run with `run_in_background=false` to preserve verification gates. Wave 1 has 10 tasks grouped:
- T1 (springdoc dep) — quick
- T2 (OpenAPI metadata + DTO @Schema) — quick (blocks T19)
- T3 (SchemaMigrator + honcho_profiles.api_version) — quick (blocks T15, T26)
- T4 (HonchoProperties + honcho.providers.strict-mode) — quick (blocks T15)
- T4a (Config-dir resilience — resolveOrCreate + per-user fallback) — quick (blocks T3)
- T4b (Structured JSONL logging + logstash-logback-encoder) — unspecified-high (blocks nothing critical)
- T5 (HonchoApiVersion + HonchoOperation enums) — quick (blocks T6, T7, T8, T10–T13)
- T6 (HonchoProvider interface) — unspecified-high (blocks T10–T13)
- T7 (HonchoClient interface, 24 methods) — unspecified-high (blocks T8, T14)
- T8 (HonchoClientFactory) — unspecified-high (blocks T15, T26, T28)

## Dependency note: T4a must run BEFORE T3

Plan line 906: "Blocks: T3 (schema migration writes honcho-inspector.db into the config dir)". So T4a first, then T3.

## JSONL log scrubbing decision

Logstash `MaskingJsonProviderDecorator` (regex-based) preferred over field-level redaction — handles Bearer tokens that may not be in a fixed field.

## Per-profile version routing

HonchoContext carries `apiVersion` (resolved: profile.apiVersion → honcho.api-version default). Profile's `api_version` column is nullable, defaults to server config.

## T4 — HonchoProperties as a record with nested Providers

Used a record (not a class) for `HonchoProperties` because:
- Constructor binding is the default for records in Spring Boot 3.x — no
  `@ConstructorBinding` annotation needed.
- Immutable by construction; all consumers read via `.apiVersion()` etc.
- Compact canonical ctor applies Java-side defaults (baseUrl, apiVersion,
  nested Providers) for the case where a drop-in config omits some keys.
Nested `Providers` is a record with `boolean strictMode` — Spring binds the
dotted YAML key `honcho.providers.strict-mode` to it.

## T4 — registration via @EnableConfigurationProperties on HttpClientConfig

Added `@EnableConfigurationProperties(HonchoProperties.class)` to the existing
`HttpClientConfig` (one-line addition; related concern: both are about the
Honcho HTTP client setup). This keeps the change isolated to config files
and one existing `@Configuration` class — no need to touch
`DashboardApplication` with `@ConfigurationPropertiesScan`.
