package com.harnkan.deal.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.harnkan.deal.domain.Deal;
import com.harnkan.deal.service.CreateDealCommand;
import com.harnkan.deal.service.DealService;
import com.harnkan.deal.service.JoinDealResult;
import com.harnkan.deal.web.dto.CreateDealRequestDto;
import com.harnkan.deal.web.dto.DealResponseDto;
import com.harnkan.deal.web.dto.JoinDealRequestDto;
import com.harnkan.deal.web.dto.JoinDealResponseDto;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class DealController {

    private final DealService dealService;

    @PostMapping("/api/deals")
    public ResponseEntity<DealResponseDto> createDeal(@RequestBody CreateDealRequestDto request) {
        Deal deal = dealService.createDeal(CreateDealCommand.builder()
                .creatorId(request.creatorId())
                .shopName(request.shopName())
                .promotionDetail(request.promotionDetail())
                .category(request.category())
                .menuImageUrl(request.menuImageUrl())
                .pricePerSlot(request.pricePerSlot())
                .targetSlots(request.targetSlots())
                .latitude(request.latitude())
                .longitude(request.longitude())
                .expiresAt(request.expiresAt())
                .build());
        return ResponseEntity.status(HttpStatus.CREATED).body(DealResponseDto.from(deal));
    }

    @GetMapping("/api/deals/{dealId}")
    public DealResponseDto getDeal(@PathVariable String dealId) {
        return DealResponseDto.from(dealService.getDeal(dealId));
    }

    @PostMapping("/api/deals/{dealId}/join")
    public JoinDealResponseDto joinDeal(@PathVariable String dealId, @RequestBody JoinDealRequestDto request) {
        JoinDealResult result = dealService.joinDeal(dealId, request.participantId());
        return JoinDealResponseDto.from(result);
    }

    @DeleteMapping("/api/deals/{dealId}")
    public DealResponseDto cancelDeal(@PathVariable String dealId, @RequestParam String requesterId) {
        return DealResponseDto.from(dealService.cancelDeal(dealId, requesterId));
    }
}
