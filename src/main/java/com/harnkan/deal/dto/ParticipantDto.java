package com.harnkan.deal.dto;

import java.time.Instant;

public record ParticipantDto(
        int slotNo,
        String userId,
        String name,
        String role,
        String status,
        Instant reservedUntil) {
}
