package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.filter.SessionAuthFilter;
import com.revytechinc.honchoinspector.model.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService auth;
    private final UserDao users;
    private final AuthSessionDao sessions;
    private final ProfileDao profiles;

    public AuthController(AuthService auth, UserDao users, AuthSessionDao sessions, ProfileDao profiles) {
        this.auth = auth;
        this.users = users;
        this.sessions = sessions;
        this.profiles = profiles;
    }

    public record CredentialsDto(
        @NotBlank String username,
        @NotBlank @Size(min = 8) String password
    ) {}

    @PostMapping("/auth/register")
    public ResponseEntity<?> register(@Valid @RequestBody CredentialsDto body) {
        try {
            var user = auth.register(body.username(), body.password());
            return ResponseEntity.status(201).body(UserResponse.from(user));
        } catch (AuthService.UserExistsException e) {
            return ResponseEntity.status(409).body(new ErrorResponse(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/auth/login")
    public ResponseEntity<?> login(@Valid @RequestBody CredentialsDto body) {
        try {
            var result = auth.login(body.username(), body.password());
            return ResponseEntity.ok(LoginResponse.of(result.session(), result.user()));
        } catch (AuthService.InvalidCredentialsException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        var sessionId = request.getHeader(SessionAuthFilter.SESSION_HEADER);
        auth.logout(sessionId);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/auth/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        var current = (AuthService.CurrentUser) request.getAttribute(SessionAuthFilter.CURRENT_USER_ATTR);
        if (current == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("not authenticated"));
        }
        return ResponseEntity.ok(UserResponse.from(current.user()));
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of(
            "ok", true,
            "users", users.count(),
            "sessions", sessions.count(),
            "profiles", profiles.count(),
            "needs_register", auth.isFirstUser()
        ));
    }
}
