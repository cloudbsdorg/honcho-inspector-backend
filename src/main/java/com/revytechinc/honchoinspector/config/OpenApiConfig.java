package com.revytechinc.honchoinspector.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI / Swagger metadata for the Honcho Inspector Backend.
 *
 * <p>Exposes a custom {@link OpenAPI} bean picked up by
 * springdoc-openapi-starter-webmvc-ui 2.6.0 (added in T1). The bean
 * populates the top-level {@code info}, the server list, and the
 * reusable tag definitions used by {@code @Tag} on the three
 * controllers.
 *
 * <p><b>Auth note:</b> this API does NOT use Bearer JWT. It uses a
 * custom {@code X-Session-Id} header. We intentionally do NOT register
 * a {@code securityScheme} claiming JWT support — doing so would
 * mislead client generators into expecting OAuth2/Bearer flows.
 *
 * @see <a href="https://springdoc.org/#how-can-i-customise-the-openapi-object">springdoc: customise the OpenAPI object</a>
 */
@Configuration
public class OpenApiConfig {

    public static final String TAG_AUTH = "auth";
    public static final String TAG_PROFILES = "profiles";
    public static final String TAG_HONCHO_PROXY = "honcho-proxy";
    public static final String TAG_ADMIN = "admin";
    public static final String TAG_SETUP = "setup";

    @Bean
    public OpenAPI honchoInspectorOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Honcho Inspector Backend")
                .version("0.1.0")
                .description("""
                    Backend for the Honcho Inspector admin surface.

                    Sits between the Angular UI and one or more Honcho instances.
                    Keeps the Honcho API key off the browser, externalizes config
                    per-OS convention, and serves a multi-user multi-profile admin
                    surface (one SQLite DB, no external service to run).

                    ## Authentication

                    This API does **not** use JWT or OAuth2 Bearer tokens. Every
                    authenticated request must carry the `X-Session-Id` header
                    whose value is the opaque session id returned by
                    `POST /api/auth/login`. The session id is a 24-byte random
                    hex string stored server-side; send it verbatim on every
                    subsequent request. `POST /api/auth/logout` invalidates it.

                    Honcho proxy endpoints additionally require the
                    `X-Honcho-Profile-Id` header to pick which encrypted profile
                    (base URL + API key + workspace) the request is routed to.
                    """)
                .contact(new Contact()
                    .name("Honcho Inspector maintainers")
                    .url("https://github.com/cloudbsdorg/honcho-inspector-backend"))
                .license(new License()
                    .name("BSD-3-Clause")
                    .url("https://opensource.org/licenses/BSD-3-Clause")))
            .servers(List.of(
                new Server()
                    .url("http://localhost:8080")
                    .description("Local dev — `mvn spring-boot:run`"),
                new Server()
                    .url("https://inspector.example.com")
                    .description("Production — behind a TLS-terminating reverse proxy")
            ))
            .tags(List.of(
                new Tag()
                    .name(TAG_AUTH)
                    .description("User registration, login, logout, and session lookup. Public endpoints (no `X-Session-Id` required) plus `/api/auth/me` and `/api/auth/logout` which require it."),
                new Tag()
                    .name(TAG_PROFILES)
                    .description("CRUD for Honcho profiles owned by the current user. Each profile stores an encrypted API key, base URL, workspace id, and Honcho user name."),
                new Tag()
                    .name(TAG_HONCHO_PROXY)
                    .description("Pass-through proxy to the Honcho v3 REST API. Every endpoint requires `X-Session-Id` AND `X-Honcho-Profile-Id`. Requests are forwarded with the profile's decrypted API key attached as `Authorization: Bearer …`."),
                new Tag()
                    .name(TAG_ADMIN)
                    .description("Mostly admin-only endpoints. Enforced by the @RequireAdmin annotation + AdminAuthInterceptor: every request must carry a session id for a user with isAdmin=true. Sub-resources: /api/admin/users (CRUD + sessions + password reset), /api/admin/audit (query the audit log), /api/admin/dashboard (aggregates + parallel Honcho fan-out), /api/admin/maintenance (manual purge + status). One exception: /api/admin/metrics/counters is reachable by any authenticated user — the values it surfaces are workspace aggregates, not admin data."),
                new Tag()
                    .name(TAG_SETUP)
                    .description("First-run configuration. Every endpoint here is reachable only when the database is empty (no users); once any user exists they all return 409. After the first admin is created via /api/setup/first-admin, all subsequent user and config management flows through the /api/admin/* surface.")
            ))
            .components(new Components());
    }
}
