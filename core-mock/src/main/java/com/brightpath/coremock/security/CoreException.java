package com.brightpath.coremock.security;

/** Vendor error with a stable vendor error code (CM-xxxx). */
public class CoreException extends RuntimeException {

    private final int status;
    private final String code;

    public CoreException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static CoreException badRequest(String code, String message) {
        return new CoreException(400, code, message);
    }

    public static CoreException forbidden(String code, String message) {
        return new CoreException(403, code, message);
    }

    public static CoreException unauthorized(String code, String message) {
        return new CoreException(401, code, message);
    }
}
