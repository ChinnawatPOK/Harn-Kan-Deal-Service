package com.harnkan.deal.exception;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/** Thrown when an outbound gRPC call to Payment Service fails. */
public class PaymentServiceException extends RuntimeException {

    private final Status.Code grpcCode;

    public PaymentServiceException(String message, Throwable cause) {
        super(message, cause);
        this.grpcCode = cause instanceof StatusRuntimeException sre ? sre.getStatus().getCode() : null;
    }

    public Status.Code getGrpcCode() {
        return grpcCode;
    }

    public boolean is(Status.Code code) {
        return grpcCode == code;
    }
}
