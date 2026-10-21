package com.brightpath.channel.security;

import io.micronaut.http.HttpRequest;
import io.micronaut.session.Session;
import io.micronaut.session.SessionStore;

import java.util.Optional;

/** Small helper for reading/creating the server-side session from an HttpRequest. */
public final class Sessions {

    private Sessions() {
    }

    public static Optional<Session> of(HttpRequest<?> request) {
        return request.getAttribute(Session.class.getName(), Session.class)
                .or(() -> request.getAttribute("micronaut.SESSION", Session.class));
    }

    /** Creates a session when none exists yet; the session filter persists it and sets the cookie. */
    public static Session resolveOrCreate(HttpRequest<?> request, SessionStore<Session> store) {
        return of(request).orElseGet(() -> {
            Session session = store.newSession();
            // The session filter reads BOTH attribute keys; set both so the cookie is
            // encoded on the response and downstream code can resolve the session.
            request.setAttribute(Session.class.getName(), session);
            request.setAttribute("micronaut.SESSION", session);
            return session;
        });
    }
}
