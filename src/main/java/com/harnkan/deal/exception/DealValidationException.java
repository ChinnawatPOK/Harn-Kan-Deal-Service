package com.harnkan.deal.exception;

/** Thrown for bad request input (missing/invalid fields), independent of transport (REST/gRPC). */
public class DealValidationException extends RuntimeException {
    public DealValidationException(String message) {
        super(message);
    }
}
