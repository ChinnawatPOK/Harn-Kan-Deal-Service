package com.harnkan.deal.domain;

import java.util.EnumSet;
import java.util.Set;

public enum ParticipantStatus {
    HOST,
    RESERVED,
    PAYMENT_PENDING,
    HELD,
    RECEIVED,
    FAILED,
    REFUND_PENDING,
    REFUNDED;

    public static final Set<ParticipantStatus> COUNTED =
            EnumSet.of(HOST, RESERVED, PAYMENT_PENDING, HELD, RECEIVED);

    public static final Set<ParticipantStatus> MONEY_AT_PAYMENT =
            EnumSet.of(PAYMENT_PENDING, HELD, REFUND_PENDING);

    public boolean isCounted() {
        return COUNTED.contains(this);
    }
}
