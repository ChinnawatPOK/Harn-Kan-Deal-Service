package com.harnkan.deal.exception;

public class DealStateException extends RuntimeException {

    private final String code;

    public DealStateException(String message) {
        this("INVALID_STATE", message);
    }

    public DealStateException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
