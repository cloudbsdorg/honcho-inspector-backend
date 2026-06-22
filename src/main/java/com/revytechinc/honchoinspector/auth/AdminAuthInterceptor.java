package com.revytechinc.honchoinspector.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.revytechinc.honchoinspector.filter.SessionAuthFilter;
import com.revytechinc.honchoinspector.model.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final ObjectMapper json;

    public AdminAuthInterceptor(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
        throws IOException {

        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }

        boolean classAnnotated  = hm.getBeanType().isAnnotationPresent(RequireAdmin.class);
        boolean methodAnnotated = hm.getMethod().isAnnotationPresent(RequireAdmin.class);
        if (!classAnnotated && !methodAnnotated) {
            return true;
        }

        var current = (AuthService.CurrentUser) request.getAttribute(SessionAuthFilter.CURRENT_USER_ATTR);
        if (current == null || !current.user().isAdmin()) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(), new ErrorResponse("admin only"));
            return false;
        }
        return true;
    }
}
