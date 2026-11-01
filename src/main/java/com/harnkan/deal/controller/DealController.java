package com.harnkan.deal.controller;

import java.time.Clock;

import com.harnkan.deal.web.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.harnkan.deal.domain.Deal;
import com.harnkan.deal.service.DealQueryService;
import com.harnkan.deal.service.DealService;
import com.harnkan.deal.service.JoinDealResult;
import com.harnkan.deal.dto.CreateDealRequestDto;
import com.harnkan.deal.dto.DealDetailDto;
import com.harnkan.deal.dto.JoinDealResponseDto;
import com.harnkan.deal.dto.MineItemDto;
import com.harnkan.deal.dto.NearbyItemDto;
import com.harnkan.deal.dto.PageDto;
import com.harnkan.deal.dto.ReceiptDto;

import lombok.RequiredArgsConstructor;


@RestController
@RequestMapping("/api/deals")
@RequiredArgsConstructor
public class DealController {

    private final DealService dealService;
    private final DealQueryService queryService;
    private final Clock clock;

    /** DR2 */
    @PostMapping
    public ResponseEntity<DealDetailDto> create(CurrentUser user, @RequestBody CreateDealRequestDto request) {
        Deal deal = dealService.createDeal(request.toCommand(user, clock));
        return ResponseEntity.status(HttpStatus.CREATED).body(queryService.detail(deal.getId(), user.id(), null, null));
    }

    /** DR3 */
    @GetMapping("/nearby")
    public PageDto<NearbyItemDto> nearby(CurrentUser user,
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "500") int radius,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queryService.nearby(lat, lng, radius, category, q, page, size);
    }

    /** DR5 */
    @GetMapping("/mine")
    public PageDto<MineItemDto> mine(CurrentUser user,
            @RequestParam(defaultValue = "joined") String tab,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queryService.mine(user.id(), tab, category, role, page, size);
    }

    /** DR4 */
    @GetMapping("/{dealId}")
    public DealDetailDto get(CurrentUser user, @PathVariable String dealId,
            @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng) {
        return queryService.detail(dealId, user.id(), lat, lng);
    }

    /** DR7 */
    @PostMapping("/{dealId}/join")
    public ResponseEntity<JoinDealResponseDto> join(CurrentUser user, @PathVariable String dealId) {
        JoinDealResult result = dealService.joinDeal(dealId, user);
        DealDetailDto detail = queryService.detail(dealId, user.id(), null, null);
        return ResponseEntity.status(HttpStatus.CREATED).body(new JoinDealResponseDto(
                result.getParticipant().getPaymentId(),
                detail.me().amountToPay(),
                result.getParticipant().getReservedUntil(),
                detail));
    }

    /** DR10 */
    @PostMapping("/{dealId}/leave")
    public DealDetailDto leave(CurrentUser user, @PathVariable String dealId) {
        dealService.leaveDeal(dealId, user);
        return queryService.detail(dealId, user.id(), null, null);
    }

    /** DR9 */
    @PostMapping("/{dealId}/confirm-receipt")
    public DealDetailDto confirmReceipt(CurrentUser user, @PathVariable String dealId) {
        dealService.confirmReceipt(dealId, user);
        return queryService.detail(dealId, user.id(), null, null);
    }

    /** DR11 */
    @GetMapping("/{dealId}/receipt")
    public ReceiptDto receipt(CurrentUser user, @PathVariable String dealId) {
        return queryService.receipt(dealId, user.id());
    }

    /** DR6 */
    @DeleteMapping("/{dealId}")
    public DealDetailDto cancel(CurrentUser user, @PathVariable String dealId) {
        dealService.cancelDeal(dealId, user.id());
        return queryService.detail(dealId, user.id(), null, null);
    }
}
