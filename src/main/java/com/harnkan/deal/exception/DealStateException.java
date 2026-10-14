package com.harnkan.deal.exception;

/** Thrown when an action is invalid for the Deal's current lifecycle status (e.g. join a full/cancelled deal). */
public class DealStateException extends RuntimeException {
    public DealStateException(String message) {
        super(message);
    }
}
