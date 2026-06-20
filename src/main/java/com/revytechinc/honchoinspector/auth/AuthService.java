package com.revytechinc.honchoinspector.auth;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class AuthService {

    private final UserDao users;
    private final AuthSessionDao sessions;
    private final PasswordHasher hasher;
    private final SecureRandom rng = new SecureRandom();

    public AuthService(UserDao users, AuthSessionDao sessions, PasswordHasher hasher) {
        this.users = users;
        this.sessions = sessions;
        this.hasher = hasher;
    }

    public boolean isFirstUser() {
        return users.count() == 0;
    }

    public User register(String username, String password) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("password must be at least 8 characters");
        }
        if (users.findByUsername(username).isPresent()) {
            throw new UserExistsException(username);
        }
        var user = new User(
            newId(),
            username.trim(),
            hasher.hash(password),
            users.count() == 0,
            Instant.now()
        );
        try {
            users.insert(user);
        } catch (DataIntegrityViolationException e) {
            throw new UserExistsException(username);
        }
        return user;
    }

    public LoginResult login(String username, String password) {
        var user = users.findByUsername(username == null ? "" : username.trim())
            .orElseThrow(InvalidCredentialsException::new);
        if (!hasher.verify(password, user.passwordHash())) {
            throw new InvalidCredentialsException();
        }
        var session = new AuthSession(
            newId(),
            user.id(),
            Instant.now(),
            Instant.now(),
            Optional.empty()
        );
        sessions.insert(session);
        return new LoginResult(session, user);
    }

    public void logout(String sessionId) {
        if (sessionId != null) sessions.deleteById(sessionId);
    }

    public Optional<CurrentUser> resolveSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return Optional.empty();
        var session = sessions.findById(sessionId).orElse(null);
        if (session == null) return Optional.empty();
        if (session.isExpired(Instant.now())) {
            sessions.deleteById(sessionId);
            return Optional.empty();
        }
        var user = users.findById(session.userId()).orElse(null);
        if (user == null) return Optional.empty();
        sessions.touch(sessionId, Instant.now());
        return Optional.of(new CurrentUser(user, session));
    }

    private String newId() {
        var bytes = new byte[24];
        rng.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public record CurrentUser(User user, AuthSession session) {}
    public record LoginResult(AuthSession session, User user) {}

    public static class UserExistsException extends RuntimeException {
        public UserExistsException(String username) { super("user already exists: " + username); }
    }

    public static class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() { super("invalid username or password"); }
    }
}
