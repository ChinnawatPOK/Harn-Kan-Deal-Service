package com.harnkan.deal.exception;

/** Thrown when the caller is not allowed to perform the requested action (e.g. cancel someone else's deal). */
public class DealPermissionException extends RuntimeException {
    public DealPermissionException(String message) {
        super(message);
    }
}
