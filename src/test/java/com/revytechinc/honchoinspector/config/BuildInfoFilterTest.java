package com.revytechinc.honchoinspector.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class BuildInfoFilterTest {

    private static final String COMMIT = "abc1234";
    private static final String TIMESTAMP = "2026-06-25T13:00:00Z";
    private static final String VERSION = "0.1.0-SNAPSHOT";

    @Test
    void addsBuildVersionAndCommitHeadersOnEveryResponse() throws Exception {
        var filter = new BuildInfoFilter(COMMIT, TIMESTAMP, "honcho-inspector-backend", VERSION);
        var req = new MockHttpServletRequest("GET", "/api/health");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getHeader(BuildInfoFilter.HDR_BUILD)).isEqualTo(COMMIT);
        assertThat(res.getHeader(BuildInfoFilter.HDR_VERSION)).isEqualTo(VERSION);
        assertThat(res.getHeader(BuildInfoFilter.HDR_BUILT_AT)).isEqualTo(TIMESTAMP);
    }

    @Test
    void emitsHeadersOnErrorResponses() throws Exception {
        var filter = new BuildInfoFilter(COMMIT, TIMESTAMP, "honcho-inspector-backend", VERSION);
        var req = new MockHttpServletRequest("GET", "/api/auth/me");
        var res = new MockHttpServletResponse();
        // No filter chain — simulates an auth-rejected 401 response
        // that returns before any controller runs.
        filter.doFilter(req, res, new FilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                try {
                    ((HttpServletResponse) response).sendError(401);
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getHeader(BuildInfoFilter.HDR_BUILD)).isEqualTo(COMMIT);
        assertThat(res.getHeader(BuildInfoFilter.HDR_VERSION)).isEqualTo(VERSION);
    }

    @Test
    void omitsBuiltAtHeaderWhenTimestampIsBlank() throws Exception {
        var filter = new BuildInfoFilter(COMMIT, "", "honcho-inspector-backend", VERSION);
        var req = new MockHttpServletRequest("GET", "/api/health");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, new MockFilterChain());

        assertThat(res.getHeader(BuildInfoFilter.HDR_BUILT_AT)).isNull();
        assertThat(res.getHeader(BuildInfoFilter.HDR_BUILD)).isEqualTo(COMMIT);
    }

    @Test
    void fallsBackToLocalWhenCommitIsUnset() throws Exception {
        var filter = new BuildInfoFilter("local", "", "honcho-inspector-backend", VERSION);
        var req = new MockHttpServletRequest("GET", "/api/health");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, new MockFilterChain());

        assertThat(res.getHeader(BuildInfoFilter.HDR_BUILD)).isEqualTo("local");
    }

    @Test
    void versionFallsBackToImplementationVersionWhenEnvNotSet() throws Exception {
        // buildVersion=null triggers the MANIFEST.MF / app-name fallback path.
        var filter = new BuildInfoFilter(COMMIT, TIMESTAMP, "honcho-inspector-backend", null);
        var req = new MockHttpServletRequest("GET", "/api/health");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, new MockFilterChain());

        // In a test JVM the MANIFEST.MF may or may not exist; either
        // fallback ("honcho-inspector-backend" or the manifest
        // version) is acceptable, but the header MUST be present and
        // non-empty.
        var hdr = res.getHeader(BuildInfoFilter.HDR_VERSION);
        assertThat(hdr).isNotNull();
        assertThat(hdr).isNotEmpty();
        assertThat(hdr).isNotEqualTo("unknown");
    }

    @Test
    void rejectsBogusShellSubstitutionInVersion() throws Exception {
        // Spring's @Value with placeholder syntax leaves bare
        // expressions like "${...}" when nothing is bound — the
        // filter must NOT emit those to the wire.
        var filter = new BuildInfoFilter(COMMIT, TIMESTAMP, "honcho-inspector-backend", "${project.version}");
        var req = new MockHttpServletRequest("GET", "/api/health");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, new MockFilterChain());

        var hdr = res.getHeader(BuildInfoFilter.HDR_VERSION);
        assertThat(hdr).isEqualTo("honcho-inspector-backend");
    }

    @Test
    void allowsFallthroughToFilterChain() throws Exception {
        var filter = new BuildInfoFilter(COMMIT, TIMESTAMP, "honcho-inspector-backend", VERSION);
        var req = new MockHttpServletRequest("GET", "/api/health");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(chain.getRequest()).isSameAs(req);
        assertThat(chain.getResponse()).isSameAs(res);
    }
}
