package com.revytechinc.honchoinspector.logging;

import com.revytechinc.honchoinspector.IntegrationTestBase;
import com.revytechinc.honchoinspector.filter.SessionAuthFilter;
import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;
import com.revytechinc.honchoinspector.honcho.HonchoClient;
import com.revytechinc.honchoinspector.honcho.HonchoClientFactory;
import com.revytechinc.honchoinspector.honcho.HonchoMockConfig;
import com.revytechinc.honchoinspector.honcho.HonchoOperation;
import com.revytechinc.honchoinspector.honcho.HonchoProvider;
import com.revytechinc.honchoinspector.honcho.v3.HonchoV3Client;
import com.revytechinc.honchoinspector.model.HonchoContext;
import com.revytechinc.honchoinspector.service.HonchoProxyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that the MDC enrichment added in T4b populates
 * {@code session_id}, {@code user_id}, {@code profile_id},
 * {@code honcho_version}, and {@code peer_id} on the request thread,
 * and clears them once the request/call completes.
 *
 * <p>{@link LogMdcTest} covers the JSONL encoder side; this class covers
 * the per-request/per-call MDC population in {@link SessionAuthFilter},
 * {@link HonchoProxyService}, and {@link HonchoV3Client}.
 */
@Import(HonchoMockConfig.class)
class MdcEnrichmentTest extends IntegrationTestBase {

    @Autowired SessionAuthFilter sessionAuthFilter;
    @Autowired HonchoProxyService proxyService;
    @Autowired HonchoV3Client v3Client;

    @BeforeEach
    void resetMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("SessionAuthFilter sets session_id + user_id MDC fields on /api/auth/me and clears them after")
    void sessionAuthFilter_setsMdcAndClears() throws Exception {
        String sessionId = registerAndLogin("alice", "alicepass123");

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/auth/me");
        req.addHeader(SessionAuthFilter.SESSION_HEADER, sessionId);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicReference<Map<String, String>> insideChainMdc = new AtomicReference<>();
        FilterChain spyChain = (request, response) -> insideChainMdc.set(MDC.getCopyOfContextMap());

        sessionAuthFilter.doFilter(req, resp, spyChain);

        assertThat(insideChainMdc.get())
            .as("SessionAuthFilter must populate session_id and user_id in MDC during the request")
            .containsEntry(SessionAuthFilter.MDC_SESSION_ID, sessionId)
            .containsKey(SessionAuthFilter.MDC_USER_ID);

        assertThat(MDC.get(SessionAuthFilter.MDC_SESSION_ID))
            .as("session_id must be cleared in the finally block")
            .isNull();
        assertThat(MDC.get(SessionAuthFilter.MDC_USER_ID))
            .as("user_id must be cleared in the finally block")
            .isNull();
    }

    @Test
    @DisplayName("SessionAuthFilter clears MDC even when downstream throws")
    void sessionAuthFilter_clearsMdcOnDownstreamError() throws Exception {
        String sessionId = registerAndLogin("alice", "alicepass123");

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/auth/me");
        req.addHeader(SessionAuthFilter.SESSION_HEADER, sessionId);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain throwingChain = (request, response) -> { throw new ServletException("boom"); };

        try {
            sessionAuthFilter.doFilter(req, resp, throwingChain);
        } catch (ServletException expected) {
            // expected
        }

        assertThat(MDC.get(SessionAuthFilter.MDC_SESSION_ID)).isNull();
        assertThat(MDC.get(SessionAuthFilter.MDC_USER_ID)).isNull();
    }

    @Test
    @DisplayName("HonchoProxyService sets profile_id + honcho_version in MDC during the upstream call")
    void proxyService_setsMdcDuringCall() throws Exception {
        String sessionId = registerAndLogin("alice", "alicepass123");
        String profileId = createProfile("mdc-test", "hnc_test_key");

        HonchoClient spyClient = mock(HonchoClient.class);
        when(spyClient.supportedVersions()).thenReturn(EnumSet.of(HonchoApiVersion.V3));
        AtomicReference<Map<String, String>> mdcAtCall = new AtomicReference<>();
        doAnswer(inv -> {
            mdcAtCall.set(MDC.getCopyOfContextMap());
            return Map.of("ok", true);
        }).when(spyClient).call(any(HonchoOperation.class), any(HonchoContext.class),
            any(), any(), any());

        HonchoClientFactory factory = new HonchoClientFactory(List.of(spyClient));

        HonchoProxyService svc = new HonchoProxyService(factory, proxyService.properties());
        HonchoContext ctx = new HonchoContext(
            "k", "https://api.honcho.dev", "ws-1", "u",
            HonchoApiVersion.V3, profileId
        );

        svc.call(HonchoOperation.LIST_PEERS, ctx, null, null, null);

        assertThat(mdcAtCall.get())
            .as("proxy call must populate profile_id and honcho_version in MDC")
            .containsEntry(HonchoProxyService.MDC_PROFILE_ID, profileId)
            .containsEntry(HonchoProxyService.MDC_HONCHO_VERSION, "v3");

        assertThat(MDC.get(HonchoProxyService.MDC_PROFILE_ID)).isNull();
        assertThat(MDC.get(HonchoProxyService.MDC_HONCHO_VERSION)).isNull();
    }

    @Test
    @DisplayName("HonchoV3Client sets peer_id in MDC when pathVars contains peerId")
    void v3Client_setsPeerIdMdc() throws Exception {
        HonchoProvider provider = mock(HonchoProvider.class);
        when(provider.operations()).thenReturn(EnumSet.allOf(HonchoOperation.class));
        when(provider.supportedVersions()).thenReturn(EnumSet.of(HonchoApiVersion.V3));
        AtomicReference<Map<String, String>> mdcAtCall = new AtomicReference<>();
        doAnswer(inv -> {
            mdcAtCall.set(MDC.getCopyOfContextMap());
            return Map.of("ok", true);
        }).when(provider).execute(any(HonchoOperation.class), any(HonchoContext.class),
            any(HonchoClient.class), any(), any(), any());

        HonchoV3Client client = new HonchoV3Client(List.of(provider));

        HonchoContext ctx = new HonchoContext(
            "k", "https://api.honcho.dev", "ws-1", "u",
            HonchoApiVersion.V3, "p1"
        );
        Map<String, String> pathVars = Map.of("peerId", "alice");

        client.call(HonchoOperation.GET_PEER_CARD, ctx, null, pathVars, null);

        assertThat(mdcAtCall.get())
            .as("v3 client call must populate peer_id in MDC when peerId is in pathVars")
            .containsEntry(HonchoV3Client.MDC_PEER_ID, "alice");

        assertThat(MDC.get(HonchoV3Client.MDC_PEER_ID)).isNull();
    }
}
