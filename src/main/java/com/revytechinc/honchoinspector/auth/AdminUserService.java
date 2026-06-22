package com.revytechinc.honchoinspector.auth;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Business logic for the admin user-management surface. All methods are
 * admin-only by virtue of being invoked from a {@code @RequireAdmin}
 * controller (enforced by {@link AdminAuthInterceptor}).
 */
@Service
public class AdminUserService {

    private final UserDao users;
    private final AuthSessionDao sessions;
    private final AuthService auth;
    private final PasswordHasher hasher;
    private final AdminAudit audit;

    public AdminUserService(
        UserDao users,
        AuthSessionDao sessions,
        AuthService auth,
        PasswordHasher hasher,
        AdminAudit audit
    ) {
        this.users = users;
        this.sessions = sessions;
        this.auth = auth;
        this.hasher = hasher;
        this.audit = audit;
    }

    public PageResult<User> list(int page, PageSize pageSize) {
        return listImpl(null, page, pageSize);
    }

    public PageResult<User> search(String query, int page, PageSize pageSize) {
        return listImpl(query, page, pageSize);
    }

    private PageResult<User> listImpl(String query, int page, PageSize pageSize) {
        int rows = pageSize.rows;
        int offset = page * rows;
        long total = (query == null || query.isBlank())
            ? users.count()
            : users.countSearchByQuery(query);
        List<User> items = (query == null || query.isBlank())
            ? users.listPaginated(rows, offset)
            : users.searchByQuery(query, rows, offset);
        int pages = rows == Integer.MAX_VALUE ? 1 : (int) Math.ceil((double) total / rows);
        return new PageResult<>(items, total, page, rows, pages);
    }

    public User get(String id) {
        return users.findById(id).orElse(null);
    }

    public CreateResult create(
        String actorId, String ip, String sessionId,
        String username, String password,
        String firstname, String lastname, String email,
        boolean isAdmin
    ) {
        if (username == null || username.isBlank()) {
            return CreateResult.error("username is required", ErrorKind.VALIDATION);
        }
        if (password == null || password.length() < 8) {
            return CreateResult.error("password must be at least 8 characters", ErrorKind.VALIDATION);
        }
        if (users.findByUsername(username).isPresent()) {
            return CreateResult.error("user already exists: " + username, ErrorKind.CONFLICT);
        }
        var user = auth.adminCreate(username, password, firstname, lastname, email, isAdmin);
        audit.record(actorId, "user.create", user.id(), null, ip, sessionId,
            java.util.Map.of("username", username, "isAdmin", isAdmin));
        return CreateResult.ok(user);
    }

    public UpdateResult update(
        String actorId, String ip, String sessionId,
        String id, String username, String firstname, String lastname, String email, Boolean isAdmin
    ) {
        var existing = users.findById(id).orElse(null);
        if (existing == null) return UpdateResult.notFound();

        String newUsername = (username == null || username.isBlank()) ? existing.username() : username.trim();
        if (!newUsername.equals(existing.username()) && users.findByUsername(newUsername).isPresent()) {
            return UpdateResult.error("user already exists: " + newUsername);
        }

        boolean newIsAdmin = isAdmin != null ? isAdmin : existing.isAdmin();
        if (!newIsAdmin && existing.isAdmin() && isLastAdmin()) {
            return UpdateResult.error("cannot demote the last admin");
        }
        if (id.equals(actorId) && existing.isAdmin() && !newIsAdmin) {
            return UpdateResult.error("cannot demote yourself");
        }

        String newFirstname = (firstname == null) ? existing.firstname() : blankToNull(firstname);
        String newLastname  = (lastname  == null) ? existing.lastname()  : blankToNull(lastname);
        String newEmail     = (email     == null) ? existing.email()     : blankToNull(email);

        users.updateIdentity(id, newUsername, newFirstname, newLastname, newEmail);
        if (newIsAdmin != existing.isAdmin()) {
            users.updateAdmin(id, newIsAdmin);
        }
        var refreshed = users.findById(id).orElseThrow();
        audit.record(actorId, "user.update", id, null, ip, sessionId,
            java.util.Map.of("username", newUsername, "isAdmin", newIsAdmin));
        return UpdateResult.ok(refreshed);
    }

    public DeleteResult delete(String actorId, String ip, String sessionId, String id) {
        var existing = users.findById(id).orElse(null);
        if (existing == null) return DeleteResult.notFound();
        if (existing.isAdmin() && isLastAdmin()) {
            return DeleteResult.error("cannot delete the last admin");
        }
        if (id.equals(actorId)) {
            return DeleteResult.error("cannot delete yourself");
        }
        users.deleteById(id);
        audit.record(actorId, "user.delete", id, null, ip, sessionId,
            java.util.Map.of("username", existing.username()));
        return DeleteResult.ok();
    }

    public List<AuthSession> sessionsForUser(String userId) {
        return sessions.findByUserId(userId);
    }

    public int revokeAllSessions(String actorId, String ip, String sessionId, String userId) {
        var existing = users.findById(userId).orElse(null);
        if (existing == null) return -1;
        int n = sessions.deleteByUserId(userId);
        audit.record(actorId, "user.sessions.revoke", userId, null, ip, sessionId,
            java.util.Map.of("revokedCount", n));
        return n;
    }

    public boolean resetPassword(String actorId, String ip, String sessionId, String userId, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            return false;
        }
        var existing = users.findById(userId).orElse(null);
        if (existing == null) return false;
        users.updatePasswordHash(userId, hasher.hash(newPassword));
        sessions.deleteByUserId(userId);
        audit.record(actorId, "user.password.reset", userId, null, ip, sessionId, java.util.Map.of());
        return true;
    }

    private boolean isLastAdmin() {
        return users.countByIsAdmin(true) <= 1;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    public record PageResult<T>(List<T> items, long total, int page, int rows, int pages) {}

    public enum ErrorKind { VALIDATION, CONFLICT }

    public record CreateResult(boolean success, User user, String error, ErrorKind kind) {
        public static CreateResult ok(User u) { return new CreateResult(true, u, null, null); }
        public static CreateResult error(String e, ErrorKind k) { return new CreateResult(false, null, e, k); }
    }

    public record UpdateResult(boolean found, User user, String error) {
        public static UpdateResult ok(User u) { return new UpdateResult(true, u, null); }
        public static UpdateResult notFound() { return new UpdateResult(false, null, null); }
        public static UpdateResult error(String e) { return new UpdateResult(true, null, e); }
    }

    public record DeleteResult(boolean found, String error) {
        public static DeleteResult ok() { return new DeleteResult(true, null); }
        public static DeleteResult notFound() { return new DeleteResult(false, null); }
        public static DeleteResult error(String e) { return new DeleteResult(true, e); }
    }
}
