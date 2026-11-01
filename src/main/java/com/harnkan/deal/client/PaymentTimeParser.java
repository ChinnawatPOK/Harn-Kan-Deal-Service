package com.harnkan.deal.client;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.harnkan.deal.exception.PaymentServiceException;

@Component
public class PaymentTimeParser {

    private final ZoneId zone;

    public PaymentTimeParser(@Value("${payment.service.timezone:UTC}") String zone) {
        this.zone = ZoneId.of(zone);
    }

    public Instant parse(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant().truncatedTo(ChronoUnit.SECONDS);
        } catch (DateTimeParseException withoutOffset) {
            try {
                return LocalDateTime.parse(value).atZone(zone).toInstant().truncatedTo(ChronoUnit.SECONDS);
            } catch (DateTimeParseException e) {
                throw new PaymentServiceException("Payment Service returned an unparseable reserved_until: " + value, e);
            }
        }
    }
}
