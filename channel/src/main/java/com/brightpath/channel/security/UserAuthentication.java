package com.brightpath.channel.security;

import io.micronaut.security.authentication.Authentication;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Authentication implementation populated from the decision tier's authorize
 * response on every request. Roles are exposed both via getRoles() and via the
 * "roles" attribute (used by the default RolesFinder).
 */
public class UserAuthentication implements Authentication {

    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_USERNAME = "username";
    public static final String ATTR_DISPLAY_NAME = "displayName";
    public static final String ATTR_EMAIL = "email";
    public static final String ATTR_ROLE = "role";
    public static final String ATTR_ROLES = "roles";
    public static final String ATTR_SCOPES = "scopes";
    public static final String ATTR_LIMITS = "limits";
    public static final String ATTR_AUTHZ_BASIS = "authzBasis";

    private final SessionUser user;
    private final Map<String, Object> attributes;

    public UserAuthentication(SessionUser user) {
        this.user = user;
        this.attributes = Map.of(
                ATTR_USER_ID, user.id(),
                ATTR_USERNAME, user.username(),
                ATTR_DISPLAY_NAME, user.displayName(),
                ATTR_EMAIL, user.email(),
                ATTR_ROLE, user.role(),
                ATTR_ROLES, List.of(user.role()),
                ATTR_SCOPES, user.studentCourses().stream()
                        .map(cid -> Map.of("courseId", cid, "scopeRole", Roles.STUDENT)).toList(),
                ATTR_LIMITS, user.limits(),
                ATTR_AUTHZ_BASIS, user.authzBasis());
    }

    public SessionUser user() {
        return user;
    }

    @Override
    public String getName() {
        return user.username();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public Collection<String> getRoles() {
        return List.of(user.role());
    }
}
