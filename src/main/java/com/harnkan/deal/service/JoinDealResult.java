package com.harnkan.deal.service;

import java.time.LocalDateTime;

import com.harnkan.deal.domain.Deal;

import lombok.Value;

@Value
public class JoinDealResult {
    Deal deal;
    String paymentId;
    LocalDateTime reservedUntil;
}
