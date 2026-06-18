package com.revytechinc.honchoinspector.controller;

import com.revytechinc.honchoinspector.auth.AuthService;
import com.revytechinc.honchoinspector.auth.ProfileService;
import com.revytechinc.honchoinspector.config.HonchoProperties;
import com.revytechinc.honchoinspector.config.OpenApiConfig;
import com.revytechinc.honchoinspector.filter.SessionAuthFilter;
import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;
import com.revytechinc.honchoinspector.honcho.HonchoCallException;
import com.revytechinc.honchoinspector.honcho.HonchoClientFactory;
import com.revytechinc.honchoinspector.model.ErrorResponse;
import com.revytechinc.honchoinspector.model.HonchoContext;
import com.revytechinc.honchoinspector.service.HonchoProxyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = OpenApiConfig.TAG_HONCHO_PROXY,
    description = """
        Pass-through proxy to the Honcho v3 REST API. Every endpoint requires both `X-Session-Id` (current user) AND `X-Honcho-Profile-Id` (which encrypted Honcho profile to use).

        Requests are forwarded verbatim to the profile's `baseUrl` with the profile's decrypted API key attached as `Authorization: Bearer …` and the profile's `honchoUserName` attached as `X-Honcho-User-Name`. The response body from Honcho is returned as-is; the proxy does not normalize, validate, or re-shape Honcho payloads.

        On Honcho HTTP 4xx the proxy returns Honcho's status code; on 5xx and transport errors the proxy returns 502.
        """)
public class HonchoController {

    public static final String PROFILE_HEADER = "X-Honcho-Profile-Id";

    private final HonchoProxyService honcho;
    private final ProfileService profiles;
    private final HonchoProperties properties;

    public HonchoController(HonchoProxyService honcho, ProfileService profiles, HonchoProperties properties) {
        this.honcho = honcho;
        this.profiles = profiles;
        this.properties = properties;
    }

    @GetMapping("/peers")
    @Operation(
        summary = "List peers in the active workspace",
        description = "Proxies `GET /v3/workspaces/{workspaceId}/peers`. Any query parameters on the inbound request are forwarded to Honcho as-is."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page of peers from Honcho (Honcho v3 `Page[T]` envelope)"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> listPeers(HttpServletRequest req,
                                       @Parameter(description = "Forwarded as query string to Honcho")
                                       @RequestParam(required = false) Map<String, String> allParams) {
        return call(req, (ctx, ws) -> honcho.listPeers(ctx, allParams));
    }

    @PostMapping("/peers")
    @Operation(
        summary = "Create a peer in the active workspace",
        description = "Proxies `POST /v3/workspaces/{workspaceId}/peers`. The request body is forwarded to Honcho unchanged."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Honcho response body (created peer)"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> createPeer(HttpServletRequest req, @RequestBody Object body) {
        return call(req, (ctx, ws) -> honcho.createPeer(ctx, body));
    }

    @GetMapping("/peers/{peerId}/card")
    @Operation(
        summary = "Get a peer's card",
        description = "Proxies `GET /v3/workspaces/{workspaceId}/peers/{peerId}/card`."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Honcho peer card (string facts)"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> peerCard(
        HttpServletRequest req,
        @Parameter(description = "Honcho peer id", example = "alice")
        @PathVariable String peerId
    ) {
        return call(req, (ctx, ws) -> honcho.getPeerCard(ctx, peerId));
    }

    @PostMapping("/peers/{peerId}/card")
    @Operation(
        summary = "Replace a peer's card",
        description = "Proxies `POST /v3/workspaces/{workspaceId}/peers/{peerId}/card`. The body (an array of fact strings) is forwarded to Honcho."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Honcho response"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> updatePeerCard(
        HttpServletRequest req,
        @Parameter(description = "Honcho peer id", example = "alice")
        @PathVariable String peerId,
        @RequestBody Object body
    ) {
        return call(req, (ctx, ws) -> honcho.updatePeerCard(ctx, peerId, body));
    }

    @GetMapping("/peers/{peerId}/representation")
    @Operation(
        summary = "Get a peer's representation",
        description = "Proxies `GET /v3/workspaces/{workspaceId}/peers/{peerId}/representation`. Returns a Honcho-formatted text representation of what the peer knows/believes."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Honcho representation string"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> peerRepresentation(
        HttpServletRequest req,
        @Parameter(description = "Honcho peer id", example = "alice")
        @PathVariable String peerId
    ) {
        return call(req, (ctx, ws) -> honcho.getPeerRepresentation(ctx, peerId));
    }

    @PostMapping("/peers/{peerId}/chat")
    @Operation(
        summary = "Chat with a peer",
        description = "Proxies `POST /v3/workspaces/{workspaceId}/peers/{peerId}/chat`. Body forwarded to Honcho unchanged."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Honcho chat response"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> peerChat(
        HttpServletRequest req,
        @Parameter(description = "Honcho peer id", example = "alice")
        @PathVariable String peerId,
        @RequestBody Object body
    ) {
        return call(req, (ctx, ws) -> honcho.peerChat(ctx, peerId, body));
    }

    @PostMapping("/peers/{peerId}/search")
    @Operation(
        summary = "Semantic search across a peer's messages",
        description = "Proxies `POST /v3/workspaces/{workspaceId}/peers/{peerId}/search`. Body forwarded to Honcho unchanged."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Honcho search results"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> peerSearch(
        HttpServletRequest req,
        @Parameter(description = "Honcho peer id", example = "alice")
        @PathVariable String peerId,
        @RequestBody Object body
    ) {
        return call(req, (ctx, ws) -> honcho.searchPeers(ctx, peerId, body));
    }

    @GetMapping("/peers/{peerId}/conclusions")
    @Operation(
        summary = "List a peer's conclusions",
        description = "Proxies `GET /v3/workspaces/{workspaceId}/peers/{peerId}/conclusions`. Query params forwarded to Honcho."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page of conclusions from Honcho"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> peerConclusions(
        HttpServletRequest req,
        @Parameter(description = "Honcho peer id", example = "alice")
        @PathVariable String peerId,
        @Parameter(description = "Forwarded as query string to Honcho (e.g. `limit`, `page`)")
        @RequestParam(required = false) Map<String, String> allParams
    ) {
        return call(req, (ctx, ws) -> honcho.listPeerConclusions(ctx, peerId, allParams));
    }

    @GetMapping("/peers/{peerId}/sessions")
    @Operation(
        summary = "List sessions a peer participates in",
        description = "Proxies `GET /v3/workspaces/{workspaceId}/peers/{peerId}/sessions`. Query params forwarded to Honcho."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page of sessions from Honcho"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> peerSessions(
        HttpServletRequest req,
        @Parameter(description = "Honcho peer id", example = "alice")
        @PathVariable String peerId,
        @Parameter(description = "Forwarded as query string to Honcho")
        @RequestParam(required = false) Map<String, String> allParams
    ) {
        return call(req, (ctx, ws) -> honcho.listPeerSessions(ctx, peerId, allParams));
    }

    @PostMapping("/peers/{peerId}/conclusions/query")
    @Operation(
        summary = "Semantic query over a peer's conclusions",
        description = "Proxies `POST /v3/workspaces/{workspaceId}/peers/{peerId}/conclusions/query`. Body forwarded to Honcho unchanged."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Honcho query response"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> peerConclusionsQuery(
        HttpServletRequest req,
        @Parameter(description = "Honcho peer id", example = "alice")
        @PathVariable String peerId,
        @RequestBody Object body
    ) {
        return call(req, (ctx, ws) -> honcho.queryPeerConclusions(ctx, peerId, body));
    }

    @GetMapping("/sessions")
    @Operation(
        summary = "List sessions in the active workspace",
        description = "Proxies `GET /v3/workspaces/{workspaceId}/sessions`. Query params forwarded to Honcho."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page of sessions from Honcho"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> listSessions(HttpServletRequest req,
                                          @Parameter(description = "Forwarded as query string to Honcho")
                                          @RequestParam(required = false) Map<String, String> allParams) {
        return call(req, (ctx, ws) -> honcho.listSessions(ctx, allParams));
    }

    @PostMapping("/sessions")
    @Operation(
        summary = "Create a session",
        description = "Proxies `POST /v3/workspaces/{workspaceId}/sessions`. Body forwarded to Honcho unchanged."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Honcho response (created session)"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> createSession(HttpServletRequest req, @RequestBody Object body) {
        return call(req, (ctx, ws) -> honcho.createSession(ctx, body));
    }

    @GetMapping("/sessions/{sessionId}")
    @Operation(
        summary = "Get a session by id",
        description = "Proxies `GET /v3/workspaces/{workspaceId}/sessions/{sessionId}`."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Honcho session record"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> getSession(
        HttpServletRequest req,
        @Parameter(description = "Honcho session id", example = "sess_abc123")
        @PathVariable String sessionId
    ) {
        return call(req, (ctx, ws) -> honcho.getSession(ctx, sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    @Operation(
        summary = "Delete a session",
        description = "Proxies `DELETE /v3/workspaces/{workspaceId}/sessions/{sessionId}`."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Honcho response (typically empty on success)"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> deleteSession(
        HttpServletRequest req,
        @Parameter(description = "Honcho session id", example = "sess_abc123")
        @PathVariable String sessionId
    ) {
        return call(req, (ctx, ws) -> honcho.deleteSession(ctx, sessionId));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    @Operation(
        summary = "List messages in a session",
        description = "Proxies `GET /v3/workspaces/{workspaceId}/sessions/{sessionId}/messages`. Query params forwarded to Honcho."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page of messages from Honcho"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> listMessages(
        HttpServletRequest req,
        @Parameter(description = "Honcho session id", example = "sess_abc123")
        @PathVariable String sessionId,
        @Parameter(description = "Forwarded as query string to Honcho (e.g. `limit`, `page`)")
        @RequestParam(required = false) Map<String, String> allParams
    ) {
        return call(req, (ctx, ws) -> honcho.listSessionMessages(ctx, sessionId, allParams));
    }

    @PostMapping("/sessions/{sessionId}/messages")
    @Operation(
        summary = "Append messages to a session",
        description = "Proxies `POST /v3/workspaces/{workspaceId}/sessions/{sessionId}/messages`. Body forwarded to Honcho unchanged."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Honcho response"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> addMessages(
        HttpServletRequest req,
        @Parameter(description = "Honcho session id", example = "sess_abc123")
        @PathVariable String sessionId,
        @RequestBody Object body
    ) {
        return call(req, (ctx, ws) -> honcho.addMessage(ctx, sessionId, body));
    }

    @GetMapping("/sessions/{sessionId}/context")
    @Operation(
        summary = "Get optimized LLM context for a session",
        description = "Proxies `GET /v3/workspaces/{workspaceId}/sessions/{sessionId}/context`. Query params forwarded to Honcho (e.g. `tokens`, `summary`)."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Honcho context payload (messages + optional summary)"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> sessionContext(
        HttpServletRequest req,
        @Parameter(description = "Honcho session id", example = "sess_abc123")
        @PathVariable String sessionId,
        @Parameter(description = "Forwarded as query string to Honcho (e.g. `tokens`, `summary=true|false`)")
        @RequestParam(required = false) Map<String, String> allParams
    ) {
        return call(req, (ctx, ws) -> honcho.getSessionContext(ctx, sessionId, tokensFrom(allParams), summaryFrom(allParams)));
    }

    @GetMapping("/sessions/{sessionId}/summaries")
    @Operation(
        summary = "List summaries for a session",
        description = "Proxies `GET /v3/workspaces/{workspaceId}/sessions/{sessionId}/summaries`."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Honcho summaries list"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> sessionSummaries(
        HttpServletRequest req,
        @Parameter(description = "Honcho session id", example = "sess_abc123")
        @PathVariable String sessionId
    ) {
        return call(req, (ctx, ws) -> honcho.getSessionSummaries(ctx, sessionId));
    }

    @GetMapping("/sessions/{sessionId}/peers")
    @Operation(
        summary = "List peers participating in a session",
        description = "Proxies `GET /v3/workspaces/{workspaceId}/sessions/{sessionId}/peers`."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Honcho peer list"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> sessionPeers(
        HttpServletRequest req,
        @Parameter(description = "Honcho session id", example = "sess_abc123")
        @PathVariable String sessionId
    ) {
        return call(req, (ctx, ws) -> honcho.getSessionPeers(ctx, sessionId));
    }

    @PostMapping("/sessions/{sessionId}/search")
    @Operation(
        summary = "Semantic search across a session's messages",
        description = "Proxies `POST /v3/workspaces/{workspaceId}/sessions/{sessionId}/search`. Body forwarded to Honcho unchanged."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Honcho search results"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> sessionSearch(
        HttpServletRequest req,
        @Parameter(description = "Honcho session id", example = "sess_abc123")
        @PathVariable String sessionId,
        @RequestBody Object body
    ) {
        return call(req, (ctx, ws) -> honcho.searchSessionMessages(ctx, sessionId, body));
    }

    @GetMapping("/queue-status")
    @Operation(
        summary = "Get background-derivation queue status",
        description = "Proxies `GET /v3/workspaces/{workspaceId}/queue/status`. Useful for showing dream/derivation backlog in the admin UI."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Honcho queue status (work-unit counts)"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> queueStatus(HttpServletRequest req) {
        return call(req, (ctx, ws) -> honcho.getQueueStatus(ctx));
    }

    @PostMapping("/search")
    @Operation(
        summary = "Workspace-wide semantic search",
        description = "Proxies `POST /v3/workspaces/{workspaceId}/search`. Body forwarded to Honcho unchanged."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Honcho search results"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> workspaceSearch(HttpServletRequest req, @RequestBody Object body) {
        return call(req, (ctx, ws) -> honcho.searchMessages(ctx, body));
    }

    @PostMapping("/dream")
    @Operation(
        summary = "Schedule a memory-consolidation dream",
        description = "Proxies `POST /v3/workspaces/{workspaceId}/schedule_dream`. Body forwarded to Honcho unchanged. The upstream v3 path is `…/peers/{peerId}/dreams`, so the proxy reads `peerId` from the request body and forwards the rest of the body to Honcho verbatim."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Honcho response (dream scheduled)"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header or body is missing the required `peerId` field",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> scheduleDream(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        if (body == null || !body.containsKey("peerId") || body.get("peerId") == null
            || body.get("peerId").toString().isBlank()) {
            return ResponseEntity.status(400)
                .body(new ErrorResponse("request body must include a non-blank 'peerId' field"));
        }
        var peerId = body.get("peerId").toString();
        return call(req, (ctx, ws) -> honcho.scheduleDream(ctx, peerId, body));
    }

    @GetMapping("/workspace/info")
    @Operation(
        summary = "Get the active workspace + queue snapshot",
        description = "Convenience composite: returns the workspace record and the current queue status in one call. Internally issues two Honcho requests (`GET /v3/workspaces/{id}` and `GET /v3/workspaces/{id}/queue/status`); if either fails the whole call fails."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Composite object `{workspace, queue}`"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> workspaceInfo(HttpServletRequest req) {
        return call(req, (ctx, wsId) -> {
            var ws = honcho.getWorkspaceInfo(ctx);
            var queue = honcho.getQueueStatus(ctx);
            return Map.of("workspace", ws, "queue", queue);
        });
    }

    private ResponseEntity<?> call(HttpServletRequest req, HonchoCall call) {
        var current = (AuthService.CurrentUser) req.getAttribute(SessionAuthFilter.CURRENT_USER_ATTR);
        if (current == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("not authenticated"));
        }
        var profileId = req.getHeader(PROFILE_HEADER);
        if (profileId == null || profileId.isBlank()) {
            return ResponseEntity.status(400).body(new ErrorResponse("missing " + PROFILE_HEADER + " header"));
        }
        var pwk = profiles.getWithKey(current.user().id(), profileId).orElse(null);
        if (pwk == null) {
            return ResponseEntity.status(404).body(new ErrorResponse("profile not found"));
        }
        var apiVersion = HonchoClientFactory.resolveVersion(
            pwk.profile().apiVersion(),
            HonchoApiVersion.fromString(properties.apiVersion())
        );
        var ctx = new HonchoContext(
            pwk.apiKey(), pwk.profile().baseUrl(),
            pwk.profile().workspaceId(), pwk.profile().honchoUserName(),
            apiVersion, profileId
        );
        try {
            var result = call.invoke(ctx, ctx.workspaceId());
            return ResponseEntity.ok(result);
        } catch (HonchoCallException e) {
            return ResponseEntity.status(e.status() >= 500 ? 502 : e.status())
                .body(Map.of("error", e.getMessage(), "body", e.body() == null ? "" : e.body()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Parse the {@code tokens} entry from a free-form query-parameter map into
     * an {@link Integer} (or {@code null} if absent/blank/non-numeric). The
     * Honcho endpoint accepts an integer token budget; we surface
     * parse failures as {@code null} so a malformed query string falls
     * through to "no override" rather than a 400 from the proxy.
     */
    private static Integer tokensFrom(Map<String, String> allParams) {
        if (allParams == null) return null;
        var raw = allParams.get("tokens");
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parse the {@code summary} entry from a free-form query-parameter map
     * into a {@link Boolean} (or {@code null} if absent/blank). Accepts
     * {@code "true"} / {@code "false"} (case-insensitive); anything else
     * returns {@code null} so the typed {@link HonchoProxyService}
     * method omits the key entirely.
     */
    private static Boolean summaryFrom(Map<String, String> allParams) {
        if (allParams == null) return null;
        var raw = allParams.get("summary");
        if (raw == null || raw.isBlank()) return null;
        return Boolean.parseBoolean(raw.trim());
    }

    @FunctionalInterface
    private interface HonchoCall {
        Object invoke(HonchoContext ctx, String workspaceId);
    }
}
