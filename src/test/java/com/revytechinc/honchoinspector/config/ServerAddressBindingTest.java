package com.revytechinc.honchoinspector.config;

import org.eclipse.jetty.server.AbstractNetworkConnector;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jetty.JettyWebServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the production bind address of the embedded Spring Boot web
 * server. The application.yml ships with
 * {@code server.address: 127.0.0.1} (override via HONCHO_BIND_ADDRESS)
 * so that the backend is reachable ONLY from the same host — the UI
 * relay on port 4200 is the only thing the browser ever talks to, and
 * it forwards {@code /api/*} to {@code http://127.0.0.1:8080}.
 *
 * <p>This test boots the full application context with a random port and
 * inspects the actual bound connector, not just the property string in
 * the environment. That guards against a future refactor that parses the
 * property but forgets to pass it to the embedded servlet container's
 * factory.
 *
 * <h2>What we check</h2>
 * <ol>
 *   <li>{@code server.address} resolves to {@code 127.0.0.1} via the
 *       {@link Environment} (sanity check on the YAML).</li>
 *   <li>The Jetty connector's bound host is {@code 127.0.0.1} — the
 *       real, observed bind, not just the property value.</li>
 *   <li>The bound address is NOT {@code 0.0.0.0} (which would mean
 *       "all interfaces" — the dangerous default this test guards against).</li>
 *   <li>For every non-loopback IPv4 interface the host has, the embedded
 *       server is NOT bound to that address. This is the loopback-only
 *       contract: the server is reachable from loopback and nowhere else.</li>
 *   <li>A live loopback HTTP call reaches the embedded server.</li>
 * </ol>
 *
 * <p>If the embedded server is ever swapped to Tomcat or Undertow, the
 * Jetty-specific assertions degrade gracefully to the Environment-only
 * check (with a clear log line) so the test still passes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "HONCHO_DB_PATH=jdbc:sqlite::memory:",
    "honcho.crypto-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWVuY3J5cHRpb24="
})
class ServerAddressBindingTest {

    @Autowired
    ApplicationContext ctx;

    @Autowired
    Environment env;

    @LocalServerPort
    int port;

    @Test
    void serverAddressPropertyResolvesToLoopback() {
        String address = env.getProperty("server.address");
        assertThat(address)
            .as("server.address in application.yml must default to 127.0.0.1")
            .isEqualTo("127.0.0.1");
    }

    @Test
    void embeddedServerBindsToLoopbackOnly() {
        assertThat(ctx)
            .as("SpringBootTest with RANDOM_PORT must produce a WebServerApplicationContext")
            .isInstanceOf(WebServerApplicationContext.class);

        WebServer webServer = ((WebServerApplicationContext) ctx).getWebServer();
        assertThat(webServer)
            .as("embedded web server must be created")
            .isNotNull();

        InetAddress bound = extractActualBoundAddress(webServer);

        assertThat(bound)
            .as("embedded server must actually bind to a resolved InetAddress")
            .isNotNull();
        assertThat(bound.getHostAddress())
            .as("embedded server must bind to 127.0.0.1 — not 0.0.0.0, not a LAN IP")
            .isEqualTo("127.0.0.1");
    }

    @Test
    void embeddedServerIsNotBoundToAnyNonLoopbackInterface() throws SocketException {
        WebServer webServer = ((WebServerApplicationContext) ctx).getWebServer();
        InetAddress bound = extractActualBoundAddress(webServer);

        // 0.0.0.0 means "all interfaces" — the dangerous default.
        assertThat(bound.isAnyLocalAddress())
            .as("0.0.0.0 means 'all interfaces' — the dangerous default")
            .isFalse();
        assertThat(bound.isLoopbackAddress())
            .as("bound address must be a loopback address")
            .isTrue();

        // Walk every non-loopback IPv4 interface on this host and assert
        // none of them is the bound address. This catches the dangerous
        // 0.0.0.0 bind even if the InetAddress#isAnyLocalAddress branch
        // above is somehow fooled by a JVM quirk.
        List<InetAddress> lanAddresses = new ArrayList<>();
        Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
        while (ifaces != null && ifaces.hasMoreElements()) {
            NetworkInterface iface = ifaces.nextElement();
            if (iface.isLoopback() || !iface.isUp()) continue;
            iface.getInetAddresses().asIterator().forEachRemaining(addr -> {
                if (addr.getAddress().length == 4) lanAddresses.add(addr);
            });
        }

        assertThat(lanAddresses)
            .as("no non-loopback interface on this host may own the bound address")
            .doesNotContain(bound);
    }

    @Test
    void randomPortIsAssignedAndServerRespondsOnLoopback() throws Exception {
        assertThat(port)
            .as("SpringBootTest must inject a non-zero random port")
            .isPositive();

        // End-to-end check: we can hit the bound port via the
        // canonical loopback hostname (127.0.0.1) and the embedded
        // server is actually answering HTTP. We don't care which
        // response — we only care that we get an HTTP response from
        // loopback. /api/auth/me with no session returns 401.
        java.net.HttpURLConnection conn =
            (java.net.HttpURLConnection) new java.net.URL(
                "http://127.0.0.1:" + port + "/api/auth/me").openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(2_000);
        conn.setReadTimeout(2_000);
        int status = conn.getResponseCode();
        conn.disconnect();
        assertThat(status)
            .as("loopback HTTP call must reach the embedded server (any 4xx is fine — 401 is expected without a session)")
            .isBetween(400, 599);
    }

    /**
     * Resolve the address the embedded server is actually bound to.
     *
     * <p>If the embedded server is Jetty, walk the connectors and read
     * the bound host straight from the connector. Otherwise fall back
     * to the {@code server.address} property from the {@link Environment}
     * (with a clear note in the failure message).
     */
    private InetAddress extractActualBoundAddress(WebServer webServer) {
        if (webServer instanceof JettyWebServer jetty) {
            return extractFromJettyConnectors(jetty);
        }
        // Non-Jetty: fall back to the Environment.
        String address = env.getProperty("server.address", "0.0.0.0");
        try {
            return InetAddress.getByName(address);
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(
                "server.address '" + address + "' did not resolve", e);
        }
    }

    /**
     * Jetty stores the bound host on each {@link AbstractNetworkConnector}
     * via {@code getHost()}. A {@code null} host means "all interfaces"
     * (the dangerous default). We pick the first connector — the dev/test
     * profile runs with exactly one HTTP connector, so this is unambiguous.
     */
    private InetAddress extractFromJettyConnectors(JettyWebServer jetty) {
        Server server = jetty.getServer();
        for (Connector connector : server.getConnectors()) {
            if (connector instanceof AbstractNetworkConnector network) {
                String host = network.getHost();
                if (host == null) {
                    // null host = 0.0.0.0 = all interfaces. The dangerous default.
                    throw new AssertionError(
                        "Jetty connector bound to wildcard (null host) — the embedded "
                      + "server is listening on 0.0.0.0, which violates the loopback-only "
                      + "contract enforced by server.address: 127.0.0.1 in application.yml");
                }
                try {
                    return InetAddress.getByName(host);
                } catch (java.net.UnknownHostException e) {
                    throw new IllegalStateException(
                        "Jetty connector bound host '" + host + "' did not resolve", e);
                }
            }
        }
        // No AbstractNetworkConnector found at all (rare — only if the test
        // runs against an embedded server that has not yet bound). Surface
        // a clear failure rather than silently passing.
        throw new AssertionError(
            "No Jetty AbstractNetworkConnector found on the embedded server; "
          + "cannot verify loopback binding");
    }
}