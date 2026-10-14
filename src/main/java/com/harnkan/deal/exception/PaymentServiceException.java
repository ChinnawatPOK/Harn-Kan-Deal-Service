package com.harnkan.deal.exception;

/** Thrown when the outbound gRPC call to Payment Service (CreatePayment/RefundDeal) fails. */
public class PaymentServiceException extends RuntimeException {
    public PaymentServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
