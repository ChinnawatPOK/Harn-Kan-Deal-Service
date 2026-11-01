package com.harnkan.deal.dto;

import java.math.BigDecimal;

/** One participant who paid; the caller's own line is named "You". */
public record ReceiptLineDto(String name, String role, BigDecimal amount) {
}
