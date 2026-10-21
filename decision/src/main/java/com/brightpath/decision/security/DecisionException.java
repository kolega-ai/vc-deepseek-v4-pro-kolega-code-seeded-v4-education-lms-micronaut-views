package com.brightpath.decision.security;

/** Domain exception with an HTTP status used across the decision tier. */
public class DecisionException extends RuntimeException {

    private final int status;

    public DecisionException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    public static DecisionException unauthorized(String message) {
        return new DecisionException(401, message);
    }

    public static DecisionException forbidden(String message) {
        return new DecisionException(403, message);
    }

    public static DecisionException badRequest(String message) {
        return new DecisionException(400, message);
    }

    public static DecisionException notFound(String message) {
        return new DecisionException(404, message);
    }
}
