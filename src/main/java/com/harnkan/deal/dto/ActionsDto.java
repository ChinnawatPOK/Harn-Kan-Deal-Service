package com.harnkan.deal.dto;

public record ActionsDto(
        boolean canJoin,
        boolean canPay,
        boolean canLeave,
        boolean canCancel,
        boolean canConfirmReceipt) {
}
