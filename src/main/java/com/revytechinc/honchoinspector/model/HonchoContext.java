package com.revytechinc.honchoinspector.model;

/**
 * Internal value object for an authenticated Honcho call. Built at the
 * controller boundary from a {@link com.revytechinc.honchoinspector.auth.Profile}
 * + its decrypted API key. Never sent to the browser.
 */
public record HonchoContext(
    String apiKey,
    String baseUrl,
    String workspaceId,
    String userName
) {
    public HonchoContext {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("apiKey required");
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl required");
        if (workspaceId == null || workspaceId.isBlank()) throw new IllegalArgumentException("workspaceId required");
        if (userName == null || userName.isBlank()) throw new IllegalArgumentException("userName required");
    }
}
