package com.brightpath.channel.security;

/** The decision tier rejected the session token (invalid, expired or revoked). */
public class DecisionUnauthorizedException extends RuntimeException {

    public DecisionUnauthorizedException(String message) {
        super(message);
    }
}
