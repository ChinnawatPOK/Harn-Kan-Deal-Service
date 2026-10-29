package com.harnkan.deal.web;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

import com.harnkan.deal.exception.UnauthorizedException;

/**
 * Resolves {@link CurrentUser}. In {@code gateway} mode the user comes from the X-User-Id / X-User-Name
 * headers the gateway sets; in {@code jwt} mode (direct calls while developing) headers are ignored
 * and the verified token is used, so a client can never forge its identity.
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_NAME = "X-User-Name";

    /** deal_participants.user_id / deals.creator_id are VARCHAR(64); user_name / creator_name are VARCHAR(100). */
    static final int MAX_ID_LENGTH = 64;
    static final int MAX_NAME_LENGTH = 100;

    private final boolean jwtMode;

    public CurrentUserArgumentResolver(boolean jwtMode) {
        this.jwtMode = jwtMode;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CurrentUser.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String id;
        String name;
        if (jwtMode) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (!(auth instanceof JwtAuthenticationToken token)) {
                throw new UnauthorizedException("Missing or invalid token");
            }
            id = token.getToken().getSubject();
            name = token.getToken().getClaimAsString("name");
        } else {
            id = webRequest.getHeader(HEADER_USER_ID);
            name = decodeHeader(webRequest.getHeader(HEADER_USER_NAME));
        }
        if (id == null || id.isBlank()) {
            throw new UnauthorizedException("Missing " + HEADER_USER_ID);
        }
        id = id.trim();
        if (id.length() > MAX_ID_LENGTH) {
            throw new UnauthorizedException("Invalid user id: longer than " + MAX_ID_LENGTH + " characters");
        }
        String display = displayName(id, name);
        if (display.length() > MAX_NAME_LENGTH) {
            display = display.substring(0, MAX_NAME_LENGTH);
        }
        return new CurrentUser(id, display);
    }

    /** The gateway percent-encodes the name so non-ASCII (Thai) names survive HTTP headers. */
    static String decodeHeader(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UriUtils.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notEncoded) {
            return value;
        }
    }

    /** Until User Service adds the `name` claim, show "User 1a2b" so rows are never blank. */
    static String displayName(String id, String name) {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        return "User " + id.substring(Math.max(0, id.length() - 4));
    }
}
