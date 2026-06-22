package com.revytechinc.honchoinspector.filter;

import com.revytechinc.honchoinspector.auth.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Component
public class SessionAuthFilter extends OncePerRequestFilter {

    public static final String CURRENT_USER_ATTR = "honcho.currentUser";
    public static final String SESSION_HEADER = "X-Session-Id";

    public static final String MDC_SESSION_ID = "session_id";
    public static final String MDC_USER_ID = "user_id";

    private static final Set<String> PUBLIC_PATHS = Set.of(
        "/api/auth/login",
        "/api/health"
    );

    private final AuthService auth;

    public SessionAuthFilter(AuthService auth) {
        this.auth = auth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {

        var path = request.getRequestURI();

        if (!path.startsWith("/api/") || PUBLIC_PATHS.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        var sessionId = request.getHeader(SESSION_HEADER);
        var current = auth.resolveSession(sessionId).orElse(null);

        if (current == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"missing or invalid session\"}");
            return;
        }

        request.setAttribute(CURRENT_USER_ATTR, current);
        try {
            if (sessionId != null) MDC.put(MDC_SESSION_ID, sessionId);
            if (current.user() != null && current.user().id() != null) MDC.put(MDC_USER_ID, current.user().id());
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_SESSION_ID);
            MDC.remove(MDC_USER_ID);
        }
    }
}
