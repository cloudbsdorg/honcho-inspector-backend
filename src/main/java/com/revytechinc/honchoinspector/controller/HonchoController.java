package com.revytechinc.honchoinspector.controller;

import com.revytechinc.honchoinspector.auth.AdminAudit;
import com.revytechinc.honchoinspector.auth.AuthService;
import com.revytechinc.honchoinspector.auth.ProfileService;
import com.revytechinc.honchoinspector.config.HonchoProperties;
import com.revytechinc.honchoinspector.config.OpenApiConfig;
import com.revytechinc.honchoinspector.filter.SessionAuthFilter;
import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;
import com.revytechinc.honchoinspector.honcho.HonchoCallException;
import com.revytechinc.honchoinspector.honcho.HonchoClientFactory;
import com.revytechinc.honchoinspector.honcho.HonchoMetrics;
import com.revytechinc.honchoinspector.honcho.StreamingChatService;
import com.revytechinc.honchoinspector.model.ErrorResponse;
import com.revytechinc.honchoinspector.model.HonchoContext;
import com.revytechinc.honchoinspector.service.HonchoProxyService;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
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
    private final AdminAudit audit;
    private final RestClient honchoRestClient;
    private final ObjectMapper json;
    private final HonchoMetrics metrics;

    public HonchoController(
        HonchoProxyService honcho,
        ProfileService profiles,
        HonchoProperties properties,
        AdminAudit audit,
        RestClient honchoRestClient,
        ObjectMapper json,
        HonchoMetrics metrics
    ) {
        this.honcho = honcho;
        this.profiles = profiles;
        this.properties = properties;
        this.audit = audit;
        this.honchoRestClient = honchoRestClient;
        this.json = json;
        this.metrics = metrics;
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

    @PutMapping("/peers/{peerId}/card")
    @Operation(
        summary = "Replace a peer's card",
        description = "Proxies `PUT /v3/workspaces/{workspaceId}/peers/{peerId}/card`. The body (an array of fact strings) is forwarded to Honcho. Recorded in the audit log as `peer_card.update` on success."
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
        Map<String, Object> metadata = Map.of("peerId", peerId, "factCount", body instanceof java.util.List<?> l ? l.size() : -1);
        return auditAndCall(
            req,
            "peer_card.update",
            "peer_card:" + peerId,
            (ctx, ws) -> honcho.updatePeerCard(ctx, peerId, body),
            metadata
        );
    }

    @PutMapping("/peers/{peerId}")
    @Operation(
        summary = "Update an existing peer",
        description = "Proxies `PUT /v3/workspaces/{workspaceId}/peers/{peerId}`. Body (Honcho v3 `PeerUpdate` shape) is forwarded to Honcho. Recorded in the audit log as `peer.update` on success."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Honcho response (updated peer record)"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> updatePeer(
        HttpServletRequest req,
        @Parameter(description = "Honcho peer id", example = "alice")
        @PathVariable String peerId,
        @RequestBody Object body
    ) {
        return auditAndCall(
            req,
            "peer.update",
            "peer:" + peerId,
            (ctx, ws) -> honcho.updatePeer(ctx, peerId, body),
            Map.of("peerId", peerId)
        );
    }

    @PostMapping("/peers/{peerId}/representation")
    @Operation(
        summary = "Get a peer's representation",
        description = "Proxies `POST /v3/workspaces/{workspaceId}/peers/{peerId}/representation` (Honcho v3 disallows GET on this endpoint). The peer provider sends an empty `{}` body when the request omits one. Returns a Honcho-formatted text representation of what the peer knows/believes."
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
        @PathVariable String peerId,
        @RequestBody(required = false) Object body
    ) {
        return call(req, (ctx, ws) -> honcho.getPeerRepresentation(ctx, peerId, body));
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

    @PostMapping(value = "/peers/{peerId}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
        summary = "Stream a chat reply from a peer",
        description = "Proxies `POST /v3/workspaces/{workspaceId}/peers/{peerId}/chat` "
            + "with `Accept: text/event-stream`. Reads Honcho's SSE chunks line by line, "
            + "strips `...` chain-of-thought blocks, and emits one `{data, error, meta}` "
            + "envelope per chunk as `data: {...}\\n\\n`. Terminal event: "
            + "`data: {data:{text:\"\"}, meta:{done:true}}\\n\\n`. Recorded in the audit "
            + "log as `chat.stream` AFTER the upstream response completes."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Stream of per-chunk SSE envelopes"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header"),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user")
    })
    public ResponseEntity<StreamingResponseBody> peerChatStream(
        HttpServletRequest req,
        @Parameter(description = "Honcho peer id", example = "alice")
        @PathVariable String peerId,
        @RequestBody(required = false) Object body
    ) {
        // Bypasses HonchoProxyService: the dispatch pipeline buffers
        // into a typed Object; SSE would defeat the purpose.
        AuthService.CurrentUser current = (AuthService.CurrentUser) req.getAttribute(SessionAuthFilter.CURRENT_USER_ATTR);
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not authenticated");
        }
        String profileIdHeader = req.getHeader(PROFILE_HEADER);
        if (profileIdHeader == null || profileIdHeader.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing " + PROFILE_HEADER + " header");
        }
        var pwk = profiles.getWithKey(current.user().id(), profileIdHeader).orElse(null);
        if (pwk == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "profile not found");
        }
        HonchoApiVersion apiVersion = HonchoClientFactory.resolveVersion(
            pwk.profile().apiVersion(),
            HonchoApiVersion.fromString(properties.apiVersion())
        );
        HonchoContext ctx = new HonchoContext(
            pwk.apiKey(), pwk.profile().baseUrl(),
            pwk.profile().workspaceId(), pwk.profile().honchoUserName(),
            apiVersion, profileIdHeader
        );
        String actorId = current.user() != null ? current.user().id() : null;
        String sessionId = req.getHeader(SessionAuthFilter.SESSION_HEADER);
        String ip = resolveClientIp(req);
        Object streamBody = body;
        if (streamBody instanceof Map<?, ?> rawMap) {
            Map<String, Object> merged = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> e : rawMap.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    merged.put(e.getKey().toString(), e.getValue());
                }
            }
            merged.putIfAbsent("stream", true);
            streamBody = merged;
        } else {
            streamBody = Map.of("stream", true);
        }
        String url = ctx.baseUrl() + "/" + ctx.apiVersion().pathPrefix()
            + "/workspaces/" + ctx.workspaceId()
            + "/peers/" + peerId + "/chat";
        ResponseEntity<InputStream> upstream;
        try {
            upstream = honchoRestClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + ctx.apiKey())
                .header("X-Honcho-User-Name", ctx.userName())
                .header("Accept", "text/event-stream")
                .contentType(MediaType.APPLICATION_JSON)
                .body(streamBody)
                .retrieve()
                .toEntity(InputStream.class);
        } catch (Exception e) {
            audit.record(actorId, "chat.stream", null, "peer:" + peerId, ip, sessionId,
                Map.of("peerId", peerId, "chunks", 0, "status", "err"));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "upstream error: " + e.getMessage());
        }
        return ResponseEntity.ok()
            .header("X-Accel-Buffering", "no")
            .header("Cache-Control", "no-cache")
            .body((StreamingResponseBody) out -> {
                long chunks = 0;
                String status = "ok";
                try (InputStream honchoStream = upstream.getBody()) {
                    chunks = StreamingChatService.stream(out, honchoStream, peerId, json);
                } catch (Throwable t) {
                    status = "err";
                    throw new RuntimeException(t);
                } finally {
                    Map<String, Object> auditMeta = new LinkedHashMap<>();
                    auditMeta.put("peerId", peerId);
                    auditMeta.put("chunks", chunks);
                    auditMeta.put("status", status);
                    auditMeta.put("apiVersion", ctx.apiVersion().pathPrefix());
                    audit.record(actorId, "chat.stream", null, "peer:" + peerId, ip, sessionId, auditMeta);
                }
            });
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

    @PostMapping("/peers/{peerId}/conclusions")
    @Operation(
        summary = "List a peer's conclusions",
        description = "Proxies `POST /v3/workspaces/{workspaceId}/conclusions/list` (Honcho v3 dropped the per-peer `/conclusions` endpoint). The body should be Honcho's `ConclusionGet` envelope: `{filters: {observed_id, size, ...}}`. The controller forwards it verbatim and also fills `observed_id` from the path variable when the body omits it, so a UI can simply POST `{}` and get the peer's full list."
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
        @RequestBody(required = false) Object body
    ) {
        return call(req, (ctx, ws) -> honcho.listPeerConclusions(ctx, peerId, body));
    }

    @PostMapping("/conclusions")
    @Operation(
        summary = "List workspace-wide conclusions (no peer filter)",
        description = "Proxies `POST /v3/workspaces/{workspaceId}/conclusions/list` without injecting `observed_id`, so Honcho returns the latest conclusions across every peer in the workspace. The body should be Honcho's `ConclusionGet` envelope: `{filters: {...}}`; an empty/missing filter object returns the most-recent N conclusions workspace-wide."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page of workspace-wide conclusions from Honcho"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> workspaceConclusions(
        HttpServletRequest req,
        @RequestBody(required = false) Object body
    ) {
        return call(req, (ctx, ws) -> honcho.listWorkspaceConclusions(ctx, body));
    }

    @PostMapping("/conclusions/create")
    @Operation(
        summary = "Batch-create conclusions in the workspace",
        description = "Proxies `POST /v3/workspaces/{workspaceId}/conclusions`. Body should be Honcho's `ConclusionBatchCreate` envelope: `{conclusions: [{content, observer_id, observed_id, session_id?}, ...]}` with up to 100 conclusions per call. The body is forwarded to Honcho verbatim — no field rewriting happens. Recorded in the audit log as `conclusion.create` on success; the `conclusionCount` metadata records how many were included in the batch. NOTE: the conclusion content itself is NOT echoed to the audit table (PII)."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Honcho response (a `Page<Conclusion>` envelope with the created rows)"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> createConclusions(
        HttpServletRequest req,
        @RequestBody Object body
    ) {
        int count = (body instanceof Map<?, ?> m && m.get("conclusions") instanceof java.util.List<?> l) ? l.size() : -1;
        Map<String, Object> metadata = Map.of("conclusionCount", count);
        return auditAndCall(
            req,
            "conclusion.create",
            "conclusion",
            (ctx, ws) -> honcho.createConclusions(ctx, body),
            metadata
        );
    }

    @DeleteMapping("/conclusions/{conclusionId}")
    @Operation(
        summary = "Delete a single conclusion by id",
        description = "Proxies `DELETE /v3/workspaces/{workspaceId}/conclusions/{conclusionId}`. Honcho returns 204 No Content on success. Recorded in the audit log as `conclusion.delete` on success."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Honcho response (typically empty on success)"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> deleteConclusion(
        HttpServletRequest req,
        @Parameter(description = "Honcho conclusion id", example = "concl_abc123")
        @PathVariable String conclusionId
    ) {
        return auditAndCall(
            req,
            "conclusion.delete",
            "conclusion:" + conclusionId,
            (ctx, ws) -> honcho.deleteConclusion(ctx, conclusionId),
            Map.of("conclusionId", conclusionId)
        );
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
        description = "Proxies `DELETE /v3/workspaces/{workspaceId}/sessions/{sessionId}`. Recorded in the audit log as `session.delete` on success."
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
        return auditAndCall(
            req,
            "session.delete",
            "session:" + sessionId,
            (ctx, ws) -> honcho.deleteSession(ctx, sessionId),
            Map.of("sessionId", sessionId)
        );
    }

    @PutMapping("/sessions/{sessionId}")
    @Operation(
        summary = "Update an existing session",
        description = "Proxies `PUT /v3/workspaces/{workspaceId}/sessions/{sessionId}`. Body (Honcho v3 `SessionUpdate` shape — e.g. `{metadata, configuration}`) is forwarded to Honcho. Recorded in the audit log as `session.update` on success."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Honcho response (updated session record)"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> updateSession(
        HttpServletRequest req,
        @Parameter(description = "Honcho session id", example = "sess_abc123")
        @PathVariable String sessionId,
        @RequestBody Object body
    ) {
        return auditAndCall(
            req,
            "session.update",
            "session:" + sessionId,
            (ctx, ws) -> honcho.updateSession(ctx, sessionId, body),
            Map.of("sessionId", sessionId)
        );
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
        ResponseEntity<?> result = call(req, (ctx, ws) -> honcho.addMessage(ctx, sessionId, body));
        if (result.getStatusCode().is2xxSuccessful()) {
            metrics.recordMessageSent(sessionId);
        }
        return result;
    }

    @PutMapping("/sessions/{sessionId}/messages/{messageId}")
    @Operation(
        summary = "Update a single message in a session",
        description = "Proxies `PUT /v3/workspaces/{workspaceId}/sessions/{sessionId}/messages/{messageId}`. Body (Honcho v3 `MessageUpdate` shape — typically `{metadata}` or `{content}`) is forwarded to Honcho. The body is NOT echoed in the audit record — only the message id and a body length are. Recorded in the audit log as `message.update` on success."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Honcho response (updated message record)"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> updateMessage(
        HttpServletRequest req,
        @Parameter(description = "Honcho session id", example = "sess_abc123")
        @PathVariable String sessionId,
        @Parameter(description = "Honcho message id", example = "msg_def456")
        @PathVariable String messageId,
        @RequestBody Object body
    ) {
        return auditAndCall(
            req,
            "message.update",
            "message:" + messageId,
            (ctx, ws) -> honcho.updateMessage(ctx, sessionId, messageId, body),
            Map.of("sessionId", sessionId, "messageId", messageId)
        );
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
        ResponseEntity<?> result = call(req, (ctx, ws) -> honcho.searchMessages(ctx, body));
        if (result.getStatusCode().is2xxSuccessful()) {
            metrics.recordSearch();
        }
        return result;
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
        ResponseEntity<?> result = call(req, (ctx, ws) -> honcho.scheduleDream(ctx, peerId, body));
        if (result.getStatusCode().is2xxSuccessful()) {
            metrics.recordDream(peerId);
        }
        return result;
    }

    @GetMapping("/workspace/info")
    @Operation(
        summary = "Get the active workspace + queue snapshot",
        description = "Convenience composite: returns the workspace record and the current queue status in one call. Honcho v3 does not expose a `GET /v3/workspaces/{id}` endpoint, so the `workspace` field is synthesized from the profile's `workspaceId` and the `queue` field is the live response from `GET /v3/workspaces/{id}/queue/status`."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Composite object `{workspace: {id}, queue}`"),
        @ApiResponse(responseCode = "400", description = "Missing `X-Honcho-Profile-Id` header",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Profile not found / not owned by current user",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> workspaceInfo(HttpServletRequest req) {
        return call(req, (ctx, wsId) -> {
            var queue = honcho.getQueueStatus(ctx);
            return Map.of("workspace", Map.of("id", wsId), "queue", queue);
        });
    }

    private ResponseEntity<Object> call(HttpServletRequest req, HonchoCall call) {
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
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result);
        } catch (HonchoCallException e) {
            return ResponseEntity.status(e.status() >= 500 ? 502 : e.status())
                .body(Map.of("error", e.getMessage(), "body", e.body() == null ? "" : e.body()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Wrap {@link #call(HttpServletRequest, HonchoCall)} so that a
     * successful (2xx) upstream Honcho call also records an entry in the
     * {@code audit_log} table via {@link AdminAudit#record}. Used on
     * every write-path endpoint (PUT/POST/DELETE) so the admin audit
     * surface has a permanent record of who did what, when.
     *
     * <p>The audit record is fire-and-forget — {@link AdminAudit#record}
     * already swallows {@code JacksonException} and {@code RuntimeException}
     * internally, so a broken audit table can never raise an exception
     * out of this method (it might log a WARN, which is the correct
     * behavior for an audit-trail anomaly).
     *
     * <p>Audit is recorded <strong>only on 2xx</strong>. We don't want
     * failed/attempted (4xx) calls in the audit trail — they represent
     * attempts that did not actually mutate Honcho state, and a noisy
     * audit table dilutes signal. The upstream Honcho status is forwarded
     * to the client as-is via {@link #call} so the operator still sees
     * the failure.
     *
     * @param req the inbound HTTP request (used to read session + IP)
     * @param action short free-text audit action (e.g. {@code peer.update})
     * @param targetResource the free-form resource identifier (e.g.
     *                       {@code peer:<peerId>}). Must be non-blank.
     * @param call the upstream dispatch lambda (built from the typed
     *             convenience methods on {@link HonchoProxyService})
     * @param metadata extra non-PII context for the audit row
     *                 (e.g. {@code {"peerId": "p-1"}})
     */
    private ResponseEntity<?> auditAndCall(
        HttpServletRequest req,
        String action,
        String targetResource,
        HonchoCall call,
        Map<String, ?> metadata
    ) {
        ResponseEntity<?> result = call(req, call);
        if (result.getStatusCode().is2xxSuccessful()) {
            var current = (AuthService.CurrentUser) req.getAttribute(SessionAuthFilter.CURRENT_USER_ATTR);
            String actorId = current != null && current.user() != null ? current.user().id() : null;
            String sessionId = req.getHeader(SessionAuthFilter.SESSION_HEADER);
            String ip = resolveClientIp(req);
            // Wrap metadata in a LinkedHashMap so AdminAudit (which calls
            // ObjectMapper.writeValueAsString on its 7th argument) sees a
            // JSON-serializable map. Map.of() returns an immutable
            // empty-map; passing a populated immutable Map.of() works
            // because Jackson can serialize any Map. Using a
            // LinkedHashMap here keeps a stable key order in the
            // serialized audit JSON for human inspection.
            Map<String, Object> serializableMetadata;
            if (metadata == null) {
                serializableMetadata = new LinkedHashMap<>();
            } else if (metadata instanceof LinkedHashMap) {
                serializableMetadata = (LinkedHashMap<String, Object>) metadata;
            } else {
                serializableMetadata = new LinkedHashMap<>(metadata);
            }
            audit.record(
                actorId,
                action,
                null,
                targetResource,
                ip,
                sessionId,
                serializableMetadata
            );
        }
        return result;
    }

    /**
     * Best-effort client-IP resolution: honour the first hop in
     * {@code X-Forwarded-For} when present (a Trust-Manager-style reverse
     * proxy), fall back to {@link HttpServletRequest#getRemoteAddr()}
     * otherwise. A blank header is ignored. Returns {@code null} only if
     * both sources are unavailable — {@link AdminAudit#record} stores
     * the {@code ip} column as nullable.
     */
    private static String resolveClientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            String first = (comma >= 0 ? xff.substring(0, comma) : xff).trim();
            if (!first.isEmpty()) return first;
        }
        return req.getRemoteAddr();
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
