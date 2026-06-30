package com.revytechinc.honchoinspector.config;

import java.io.File;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the bundled Angular UI's static dist as the operator's
 * frontend, when the all-in-one Containerfile is in use.
 *
 * <p>The all-in-one image bakes the UI's `ng build` output at
 * <code>/usr/local/share/honcho-inspector-ui/</code> (set via the
 * <code>honcho.ui.dist</code> property, which the Dockerfile maps to
 * an env var of the same name). The Spring Boot app exposes
 * <code>/api/*</code> from its controllers and now also serves the
 * static files at <code>/</code>, with SPA-style fallback for any
 * non-<code>/api</code>, non-<code>/actuator</code> path so the
 * Angular router takes over.
 *
 * <p>When the dist directory is absent (the standalone backend image,
 * where the operator runs the UI as a separate container on :4200),
 * the static handler is skipped and <code>addViewControllers</code>
 * short-circuits -- in that case Spring's default error page handles
 * the route and the operator's reverse proxy is expected to forward
 * <code>/</code> to the UI.
 *
 * <h2>Why a single config class instead of two</h2>
 * <p>CorsConfig and AdminAuthConfig already implement
 * WebMvcConfigurer to layer on cross-cutting behavior; this one adds
 * the SPA serve. Spring picks them all up via the
 * <code>@Configuration</code> stereotype; the order among the
 * WebMvcConfigurer beans is preserved as long as we don't use
 * <code>@Order</code> (we don't -- there's no ordering constraint
 * between these handlers).
 */
@Configuration
public class UiStaticConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(UiStaticConfig.class);

    private final File uiDistDir;

    public UiStaticConfig(
        @Value("${HONCHO_UI_DIST:/usr/local/share/honcho-inspector/ui}") String uiDistPath
    ) {
        this.uiDistDir = new File(uiDistPath);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Bail out gracefully if the dist directory isn't present
        // (i.e. we're running the standalone backend image rather
        // than the all-in-one). Static serving is a no-op then and
        // every non-/api request hits the default error page, which
        // is the right behavior for the standalone deployment.
        if (!uiDistDir.isDirectory()) {
            log.info("UI dist directory not present at {} -- SPA serving disabled", uiDistDir.getAbsolutePath());
            return;
        }

        File indexHtml = new File(uiDistDir, "index.html");
        if (!indexHtml.isFile()) {
            log.warn("UI dist found at {} but missing index.html -- SPA serving disabled", uiDistDir.getAbsolutePath());
            return;
        }

        log.info("Serving UI SPA from {}", uiDistDir.getAbsolutePath());

        // Serve everything under `/assets/**`, `/**.(svg|png|ico|css|js|...)`
        // directly off disk. The PathResourceResolver below handles
        // the SPA fallback (any path that doesn't resolve to a file
        // falls back to index.html).
        registry.addResourceHandler("/**")
            .addResourceLocations("file:" + uiDistDir.getAbsolutePath() + "/")
            .resourceChain(true)
            .addResolver(new SpaFallbackResourceResolver(uiDistDir));
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // `addResourceHandler("/**")` catches /foo, /bar, etc. but NOT the
        // empty path `/` -- Spring's handler chain only matches `/` when
        // a view controller explicitly maps it, otherwise the request
        // 404s. Forward `/` -> /index.html so a hard browser reload on
        // the root URL serves the SPA shell.
        //
        // Same dist-presence gate as addResourceHandlers so the
        // standalone-backend deployment behavior (default error page on
        // `/`) is unchanged.
        if (!uiDistDir.isDirectory()) {
            return;
        }
        File indexHtml = new File(uiDistDir, "index.html");
        if (!indexHtml.isFile()) {
            return;
        }
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    /**
     * PathResourceResolver that serves index.html for any path that
     * doesn't resolve to a real file on disk. Required so Angular's
     * router can take /profiles, /admin, /inspector etc. on a hard
     * reload rather than 404'ing them.
     *
     * <p>API and actuator paths are NOT rewritten -- they fall
     * through to Spring's normal request mapping and end up in the
     * backend's controllers. The controller path mappings always run
     * before the resource handler, so this resolver only catches
     * paths that didn't match a controller -- which is exactly what
     * we want.
     */
    private static final class SpaFallbackResourceResolver extends PathResourceResolver {
        private final File indexHtml;

        SpaFallbackResourceResolver(File uiDistDir) {
            this.indexHtml = new File(uiDistDir, "index.html");
        }

        // Spring 7.x PathResourceResolver exposes the (String, Resource)
        // overload as the protected hook. The (String, URI) variant
        // exists on a different superclass interface and is NOT
        // overridable here -- override the one that compiles against
        // the current Spring runtime and the backend stays portable
        // across 6.x and 7.x because the (String, Resource) signature
        // has been stable since Spring's resource handler shipped.
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws java.io.IOException {
            // 1) Try the literal path first (e.g. /assets/foo.css).
            Resource requested = super.getResource(resourcePath, location);
            if (requested != null && requested.exists()) {
                return requested;
            }

            // 2) Fall back to index.html for SPA routing.
            return new FileSystemResource(indexHtml);
        }
    }
}
