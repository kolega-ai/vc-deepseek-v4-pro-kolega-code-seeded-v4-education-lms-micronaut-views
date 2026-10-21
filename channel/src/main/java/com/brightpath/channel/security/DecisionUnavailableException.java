package com.brightpath.channel.security;

/** The decision tier could not be reached; the channel fails closed. */
public class DecisionUnavailableException extends RuntimeException {

    public DecisionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
