package com.revytechinc.honchoinspector.controller;

import com.revytechinc.honchoinspector.auth.AuthService;
import com.revytechinc.honchoinspector.auth.ProfileService;
import com.revytechinc.honchoinspector.filter.SessionAuthFilter;
import com.revytechinc.honchoinspector.model.ErrorResponse;
import com.revytechinc.honchoinspector.model.HonchoContext;
import com.revytechinc.honchoinspector.service.HonchoProxyService;
import com.revytechinc.honchoinspector.service.HonchoProxyService.HonchoCallException;
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
public class HonchoController {

    public static final String PROFILE_HEADER = "X-Honcho-Profile-Id";

    private final HonchoProxyService honcho;
    private final ProfileService profiles;

    public HonchoController(HonchoProxyService honcho, ProfileService profiles) {
        this.honcho = honcho;
        this.profiles = profiles;
    }

    @GetMapping("/peers")
    public ResponseEntity<?> listPeers(HttpServletRequest req,
                                       @RequestParam(required = false) Map<String, String> allParams) {
        return call(req, (ctx, ws) -> honcho.get(ctx, "/v3/workspaces/" + ws + "/peers", allParams));
    }

    @PostMapping("/peers")
    public ResponseEntity<?> createPeer(HttpServletRequest req, @RequestBody Object body) {
        return call(req, (ctx, ws) -> honcho.post(ctx, "/v3/workspaces/" + ws + "/peers", null, body));
    }

    @GetMapping("/peers/{peerId}/card")
    public ResponseEntity<?> peerCard(HttpServletRequest req, @PathVariable String peerId) {
        return call(req, (ctx, ws) -> honcho.get(ctx, "/v3/workspaces/" + ws + "/peers/" + peerId + "/card", null));
    }

    @PostMapping("/peers/{peerId}/card")
    public ResponseEntity<?> updatePeerCard(HttpServletRequest req, @PathVariable String peerId, @RequestBody Object body) {
        return call(req, (ctx, ws) -> honcho.put(ctx, "/v3/workspaces/" + ws + "/peers/" + peerId + "/card", null, body));
    }

    @GetMapping("/peers/{peerId}/representation")
    public ResponseEntity<?> peerRepresentation(HttpServletRequest req, @PathVariable String peerId) {
        return call(req, (ctx, ws) -> honcho.get(ctx, "/v3/workspaces/" + ws + "/peers/" + peerId + "/representation", null));
    }

    @PostMapping("/peers/{peerId}/chat")
    public ResponseEntity<?> peerChat(HttpServletRequest req, @PathVariable String peerId, @RequestBody Object body) {
        return call(req, (ctx, ws) -> honcho.post(ctx, "/v3/workspaces/" + ws + "/peers/" + peerId + "/chat", null, body));
    }

    @PostMapping("/peers/{peerId}/search")
    public ResponseEntity<?> peerSearch(HttpServletRequest req, @PathVariable String peerId, @RequestBody Object body) {
        return call(req, (ctx, ws) -> honcho.post(ctx, "/v3/workspaces/" + ws + "/peers/" + peerId + "/search", null, body));
    }

    @GetMapping("/peers/{peerId}/conclusions")
    public ResponseEntity<?> peerConclusions(HttpServletRequest req, @PathVariable String peerId,
                                            @RequestParam(required = false) Map<String, String> allParams) {
        return call(req, (ctx, ws) -> honcho.get(ctx, "/v3/workspaces/" + ws + "/peers/" + peerId + "/conclusions", allParams));
    }

    @GetMapping("/peers/{peerId}/sessions")
    public ResponseEntity<?> peerSessions(HttpServletRequest req, @PathVariable String peerId,
                                          @RequestParam(required = false) Map<String, String> allParams) {
        return call(req, (ctx, ws) -> honcho.get(ctx, "/v3/workspaces/" + ws + "/peers/" + peerId + "/sessions", allParams));
    }

    @PostMapping("/peers/{peerId}/conclusions/query")
    public ResponseEntity<?> peerConclusionsQuery(HttpServletRequest req, @PathVariable String peerId, @RequestBody Object body) {
        return call(req, (ctx, ws) -> honcho.post(ctx, "/v3/workspaces/" + ws + "/peers/" + peerId + "/conclusions/query", null, body));
    }

    @GetMapping("/sessions")
    public ResponseEntity<?> listSessions(HttpServletRequest req,
                                          @RequestParam(required = false) Map<String, String> allParams) {
        return call(req, (ctx, ws) -> honcho.get(ctx, "/v3/workspaces/" + ws + "/sessions", allParams));
    }

    @PostMapping("/sessions")
    public ResponseEntity<?> createSession(HttpServletRequest req, @RequestBody Object body) {
        return call(req, (ctx, ws) -> honcho.post(ctx, "/v3/workspaces/" + ws + "/sessions", null, body));
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<?> getSession(HttpServletRequest req, @PathVariable String sessionId) {
        return call(req, (ctx, ws) -> honcho.get(ctx, "/v3/workspaces/" + ws + "/sessions/" + sessionId, null));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<?> deleteSession(HttpServletRequest req, @PathVariable String sessionId) {
        return call(req, (ctx, ws) -> honcho.delete(ctx, "/v3/workspaces/" + ws + "/sessions/" + sessionId));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<?> listMessages(HttpServletRequest req, @PathVariable String sessionId,
                                          @RequestParam(required = false) Map<String, String> allParams) {
        return call(req, (ctx, ws) -> honcho.get(ctx, "/v3/workspaces/" + ws + "/sessions/" + sessionId + "/messages", allParams));
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<?> addMessages(HttpServletRequest req, @PathVariable String sessionId, @RequestBody Object body) {
        return call(req, (ctx, ws) -> honcho.post(ctx, "/v3/workspaces/" + ws + "/sessions/" + sessionId + "/messages", null, body));
    }

    @GetMapping("/sessions/{sessionId}/context")
    public ResponseEntity<?> sessionContext(HttpServletRequest req, @PathVariable String sessionId,
                                            @RequestParam(required = false) Map<String, String> allParams) {
        return call(req, (ctx, ws) -> honcho.get(ctx, "/v3/workspaces/" + ws + "/sessions/" + sessionId + "/context", allParams));
    }

    @GetMapping("/sessions/{sessionId}/summaries")
    public ResponseEntity<?> sessionSummaries(HttpServletRequest req, @PathVariable String sessionId) {
        return call(req, (ctx, ws) -> honcho.get(ctx, "/v3/workspaces/" + ws + "/sessions/" + sessionId + "/summaries", null));
    }

    @GetMapping("/sessions/{sessionId}/peers")
    public ResponseEntity<?> sessionPeers(HttpServletRequest req, @PathVariable String sessionId) {
        return call(req, (ctx, ws) -> honcho.get(ctx, "/v3/workspaces/" + ws + "/sessions/" + sessionId + "/peers", null));
    }

    @PostMapping("/sessions/{sessionId}/search")
    public ResponseEntity<?> sessionSearch(HttpServletRequest req, @PathVariable String sessionId, @RequestBody Object body) {
        return call(req, (ctx, ws) -> honcho.post(ctx, "/v3/workspaces/" + ws + "/sessions/" + sessionId + "/search", null, body));
    }

    @GetMapping("/queue-status")
    public ResponseEntity<?> queueStatus(HttpServletRequest req) {
        return call(req, (ctx, ws) -> honcho.get(ctx, "/v3/workspaces/" + ws + "/queue/status", null));
    }

    @PostMapping("/search")
    public ResponseEntity<?> workspaceSearch(HttpServletRequest req, @RequestBody Object body) {
        return call(req, (ctx, ws) -> honcho.post(ctx, "/v3/workspaces/" + ws + "/search", null, body));
    }

    @PostMapping("/dream")
    public ResponseEntity<?> scheduleDream(HttpServletRequest req, @RequestBody Object body) {
        return call(req, (ctx, ws) -> honcho.post(ctx, "/v3/workspaces/" + ws + "/schedule_dream", null, body));
    }

    @GetMapping("/workspace/info")
    public ResponseEntity<?> workspaceInfo(HttpServletRequest req) {
        return call(req, (ctx, wsId) -> {
            var ws = honcho.get(ctx, "/v3/workspaces/" + wsId, null);
            var queue = honcho.get(ctx, "/v3/workspaces/" + wsId + "/queue/status", null);
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
        var ctx = new HonchoContext(
            pwk.apiKey(), pwk.profile().baseUrl(),
            pwk.profile().workspaceId(), pwk.profile().honchoUserName()
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

    @FunctionalInterface
    private interface HonchoCall {
        Object invoke(HonchoContext ctx, String workspaceId);
    }
}
