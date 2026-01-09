package com.fulfillops.shared.web;

import org.springframework.http.HttpStatus;

/** Base for business-rule violations that map to a specific HTTP status and stable error code. */
public class DomainException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public DomainException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static DomainException notFound(String what, Object id) {
        return new DomainException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " " + id + " not found");
    }
}
