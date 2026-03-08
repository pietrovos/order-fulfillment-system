package com.fulfillops.shared.jobs;

/** The job can never succeed by retrying (bad payload, rejected request); mark it FAILED now. */
public class PermanentJobFailure extends RuntimeException {

    public PermanentJobFailure(String message) {
        super(message);
    }

    public PermanentJobFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
