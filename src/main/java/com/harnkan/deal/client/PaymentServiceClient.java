package com.harnkan.deal.client;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.harnkan.deal.exception.PaymentServiceException;
import com.harnkan.deal.grpc.payment.CancelPaymentRequest;
import com.harnkan.deal.grpc.payment.CancelPaymentResponse;
import com.harnkan.deal.grpc.payment.ConfirmReceiptRequest;
import com.harnkan.deal.grpc.payment.ConfirmReceiptResponse;
import com.harnkan.deal.grpc.payment.CreatePaymentRequest;
import com.harnkan.deal.grpc.payment.CreatePaymentResponse;
import com.harnkan.deal.grpc.payment.PaymentServiceGrpc;
import com.harnkan.deal.grpc.payment.RefundDealRequest;
import com.harnkan.deal.grpc.payment.RefundDealResponse;
import com.harnkan.deal.grpc.payment.ReleaseFundsRequest;
import com.harnkan.deal.grpc.payment.ReleaseFundsResponse;

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

    public CancelPaymentResponse cancelPayment(String paymentId) {
        try {
            return paymentServiceBlockingStub.cancelPayment(
                    CancelPaymentRequest.newBuilder().setPaymentId(paymentId).build());
        } catch (StatusRuntimeException e) {
            throw new PaymentServiceException(
                    "Payment Service CancelPayment failed for payment " + paymentId + ": " + describe(e), e);
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

    public ConfirmReceiptResponse confirmReceipt(String paymentId, String participantId) {
        try {
            return paymentServiceBlockingStub.confirmReceipt(ConfirmReceiptRequest.newBuilder()
                    .setPaymentId(paymentId).setParticipantId(participantId).build());
        } catch (StatusRuntimeException e) {
            throw new PaymentServiceException(
                    "Payment Service ConfirmReceipt failed for payment " + paymentId + ": " + describe(e), e);
        }
    }

    public ReleaseFundsResponse releaseFunds(String dealId, String creatorId) {
        try {
            return paymentServiceBlockingStub.releaseFunds(ReleaseFundsRequest.newBuilder()
                    .setDealId(dealId).setCreatorId(creatorId).build());
        } catch (StatusRuntimeException e) {
            throw new PaymentServiceException(
                    "Payment Service ReleaseFunds failed for deal " + dealId + ": " + describe(e), e);
        }
    }

    private static String describe(StatusRuntimeException e) {
        return e.getStatus().getCode() + (e.getStatus().getDescription() != null
                ? " - " + e.getStatus().getDescription()
                : "");
    }
}
