package com.revytechinc.honchoinspector.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller or controller method as admin-only.
 *
 * <p>Enforced centrally by {@link AdminAuthInterceptor}, which runs after
 * {@code SessionAuthFilter} resolves the session and before the controller
 * method is invoked. If the current user's {@code isAdmin} flag is false,
 * the interceptor returns {@code 403 Forbidden} with the standard
 * {@code ErrorResponse} envelope and the controller method is never called.
 *
 * <p>Apply at the class level (every method is admin-only) or the method
 * level (only that method). Method-level takes precedence — a class-level
 * annotation can be overridden by a method without the annotation, though
 * the typical pattern is the inverse: most admin controllers use the
 * class-level form.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAdmin {
}
