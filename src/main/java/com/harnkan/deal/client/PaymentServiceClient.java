package com.harnkan.deal.client;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.harnkan.deal.exception.PaymentServiceException;
import com.harnkan.deal.grpc.payment.CreatePaymentRequest;
import com.harnkan.deal.grpc.payment.CreatePaymentResponse;
import com.harnkan.deal.grpc.payment.PaymentServiceGrpc;
import com.harnkan.deal.grpc.payment.RefundDealRequest;
import com.harnkan.deal.grpc.payment.RefundDealResponse;

import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentServiceClient {

    private final PaymentServiceGrpc.PaymentServiceBlockingStub paymentServiceBlockingStub;

    public CreatePaymentResponse createPayment(String dealId, String participantId, BigDecimal amount) {
        try {
            CreatePaymentRequest request = CreatePaymentRequest.newBuilder()
                    .setDealId(dealId)
                    .setParticipantId(participantId)
                    .setAmount(amount.toPlainString())
                    .build();
            return paymentServiceBlockingStub.createPayment(request);
        } catch (StatusRuntimeException e) {
            throw new PaymentServiceException(
                    "Payment Service CreatePayment failed for deal " + dealId + ": " + describe(e), e);
        }
    }

    public RefundDealResponse refundDeal(String dealId) {
        try {
            RefundDealRequest request = RefundDealRequest.newBuilder().setDealId(dealId).build();
            return paymentServiceBlockingStub.refundDeal(request);
        } catch (StatusRuntimeException e) {
            throw new PaymentServiceException(
                    "Payment Service RefundDeal failed for deal " + dealId + ": " + describe(e), e);
        }
    }

    private static String describe(StatusRuntimeException e) {
        return e.getStatus().getCode() + (e.getStatus().getDescription() != null
                ? " - " + e.getStatus().getDescription()
                : "");
    }
}
