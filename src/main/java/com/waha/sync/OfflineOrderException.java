package com.waha.sync;

// A refused offline order push. retryable=false means the kiosk must stop retrying this order
// (it goes to its dead-letter list); status 409 is a conflict with data the server already holds.
public class OfflineOrderException extends RuntimeException {

    private final String code;
    private final boolean retryable;
    private final int status;

    public OfflineOrderException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.retryable = false;
        this.status = status;
    }

    public static OfflineOrderException invalid(String code, String message) {
        return new OfflineOrderException(code, message, 422);
    }

    public static OfflineOrderException conflict(String code, String message) {
        return new OfflineOrderException(code, message, 409);
    }

    public String code() { return code; }
    public boolean retryable() { return retryable; }
    public int status() { return status; }
}
