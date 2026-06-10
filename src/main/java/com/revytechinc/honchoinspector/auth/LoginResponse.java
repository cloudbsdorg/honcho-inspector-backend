package com.revytechinc.honchoinspector.auth;

public record LoginResponse(
    String sessionId,
    UserResponse user
) {
    public static LoginResponse of(AuthSession session, User user) {
        return new LoginResponse(session.id(), UserResponse.from(user));
    }
}
