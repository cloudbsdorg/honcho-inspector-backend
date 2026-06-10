package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.filter.SessionAuthFilter;
import com.revytechinc.honchoinspector.model.ErrorResponse;
import com.revytechinc.honchoinspector.model.HonchoContext;
import com.revytechinc.honchoinspector.service.HonchoProxyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/profiles")
public class ProfileController {

    private final ProfileService profiles;
    private final HonchoProxyService honcho;

    public ProfileController(ProfileService profiles, HonchoProxyService honcho) {
        this.profiles = profiles;
        this.honcho = honcho;
    }

    public record ProfileCreateDto(
        @NotBlank String label,
        @NotBlank String apiKey,
        @NotBlank String baseUrl,
        @NotBlank String workspaceId,
        @NotBlank String honchoUserName
    ) {}

    public record ProfileUpdateDto(
        String label,
        String apiKey,
        String baseUrl,
        String workspaceId,
        String honchoUserName
    ) {}

    public record ProfileWithKeyDto(Profile profile, String apiKey) {}

    @GetMapping
    public ResponseEntity<List<Profile>> list(HttpServletRequest req) {
        var current = currentUser(req);
        return ResponseEntity.ok(profiles.list(current.user().id()));
    }

    @PostMapping
    public ResponseEntity<?> create(HttpServletRequest req, @Valid @RequestBody ProfileCreateDto body) {
        var current = currentUser(req);
        var p = profiles.create(
            current.user().id(),
            body.label(), body.apiKey(), body.baseUrl(),
            body.workspaceId(), body.honchoUserName()
        );
        return ResponseEntity.status(201).body(p);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(HttpServletRequest req, @PathVariable String id) {
        var current = currentUser(req);
        return profiles.get(current.user().id(), id)
            .<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.status(404).body(new ErrorResponse("profile not found")));
    }

    @GetMapping("/{id}/reveal")
    public ResponseEntity<?> reveal(HttpServletRequest req, @PathVariable String id) {
        var current = currentUser(req);
        return profiles.getWithKey(current.user().id(), id)
            .map(pwk -> ResponseEntity.ok((Object) new ProfileWithKeyDto(pwk.profile(), pwk.apiKey())))
            .orElseGet(() -> ResponseEntity.status(404).body(new ErrorResponse("profile not found")));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
        HttpServletRequest req,
        @PathVariable String id,
        @RequestBody ProfileUpdateDto body
    ) {
        var current = currentUser(req);
        return profiles.update(
            current.user().id(), id,
            body.label(), body.apiKey(), body.baseUrl(),
            body.workspaceId(), body.honchoUserName()
        ).<ResponseEntity<?>>map(ResponseEntity::ok)
         .orElseGet(() -> ResponseEntity.status(404).body(new ErrorResponse("profile not found")));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(HttpServletRequest req, @PathVariable String id) {
        var current = currentUser(req);
        var ok = profiles.delete(current.user().id(), id);
        return ok
            ? ResponseEntity.noContent().build()
            : ResponseEntity.status(404).body(new ErrorResponse("profile not found"));
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<?> test(HttpServletRequest req, @PathVariable String id) {
        var current = currentUser(req);
        var pwk = profiles.getWithKey(current.user().id(), id).orElse(null);
        if (pwk == null) {
            return ResponseEntity.status(404).body(new ErrorResponse("profile not found"));
        }
        var ctx = new HonchoContext(pwk.apiKey(), pwk.profile().baseUrl(),
                                    pwk.profile().workspaceId(), pwk.profile().honchoUserName());
        try {
            honcho.testConnection(ctx);
            return ResponseEntity.ok(Map.of("ok", true, "message", "reachable"));
        } catch (HonchoProxyService.HonchoCallException e) {
            return ResponseEntity.status(e.status() >= 500 ? 502 : e.status())
                .body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    private AuthService.CurrentUser currentUser(HttpServletRequest req) {
        return (AuthService.CurrentUser) req.getAttribute(SessionAuthFilter.CURRENT_USER_ATTR);
    }
}
