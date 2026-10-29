package com.harnkan.deal.service;

import com.harnkan.deal.domain.Deal;
import com.harnkan.deal.domain.DealParticipant;

import lombok.Value;

@Value
public class JoinDealResult {
    Deal deal;
    DealParticipant participant;
}
