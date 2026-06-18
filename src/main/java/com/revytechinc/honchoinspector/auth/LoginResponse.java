package com.revytechinc.honchoinspector.auth;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
    name = "LoginResponse",
    description = "Returned by `POST /api/auth/login` and `POST /api/auth/register` (register omits the sessionId). The `sessionId` is the value to send in the `X-Session-Id` header on every subsequent request.",
    example = "{\"sessionId\":\"5f4dcc3b5aa765d61d8327deb882cf99\",\"user\":{\"id\":\"a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4\",\"username\":\"alice\",\"isAdmin\":true,\"createdAt\":\"2026-06-17T22:00:00Z\"}}"
)
public record LoginResponse(
    @Schema(description = "Opaque 24-byte hex session id. Send verbatim in the `X-Session-Id` request header.", example = "5f4dcc3b5aa765d61d8327deb882cf99")
    String sessionId,

    @Schema(description = "The authenticated user record")
    UserResponse user
) {
    public static LoginResponse of(AuthSession session, User user) {
        return new LoginResponse(session.id(), UserResponse.from(user));
    }
}
