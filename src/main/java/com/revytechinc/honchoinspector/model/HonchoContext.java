package com.revytechinc.honchoinspector.model;

import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;

/**
 * Internal value object for an authenticated Honcho call. Built at the
 * controller boundary from a {@link com.revytechinc.honchoinspector.auth.Profile}
 * + its decrypted API key. Never sent to the browser.
 *
 * <p>Carries the resolved {@link HonchoApiVersion} so downstream code
 * (e.g. {@code HonchoProxyService}, providers) can pick the correct
 * client/URL without re-resolving the version per call. T15 and T16
 * will populate {@code apiVersion} from the resolved version; until
 * then the no-version constructor defaults to {@link HonchoApiVersion#V3}
 * which matches the {@code honcho.api-version} default.
 */
public record HonchoContext(
    String apiKey,
    String baseUrl,
    String workspaceId,
    String userName,
    HonchoApiVersion apiVersion
) {
    public HonchoContext {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("apiKey required");
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl required");
        if (workspaceId == null || workspaceId.isBlank()) throw new IllegalArgumentException("workspaceId required");
        if (userName == null || userName.isBlank()) throw new IllegalArgumentException("userName required");
        if (apiVersion == null) throw new IllegalArgumentException("apiVersion required");
    }

    /**
     * Backward-compatible constructor. Delegates to the canonical
     * 5-arg form with {@link HonchoApiVersion#V3} (the product default).
     * Kept in place so existing call sites — {@code HonchoController},
     * {@code ProfileController}, and the {@code HonchoProviderSkeletonTest}
     * fixture — continue to compile until T15/T16 update them.
     */
    public HonchoContext(String apiKey, String baseUrl, String workspaceId, String userName) {
        this(apiKey, baseUrl, workspaceId, userName, HonchoApiVersion.V3);
    }
}
