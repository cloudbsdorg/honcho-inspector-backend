package com.revytechinc.honchoinspector.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
    name = "ErrorResponse",
    description = "Generic error envelope returned for non-2xx responses. The `error` field carries a short human-readable message suitable for surfacing in the UI; it is not localized and not intended for programmatic branching.",
    example = "{\"error\": \"invalid username or password\"}"
)
public record ErrorResponse(String error) {}
