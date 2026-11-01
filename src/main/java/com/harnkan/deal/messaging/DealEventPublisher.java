package com.harnkan.deal.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.harnkan.deal.domain.Deal;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class DealEventPublisher {

    public static final String DEAL_CREATED = "deal.created";
    public static final String DEAL_CANCELLED = "deal.cancelled";
    public static final String DEAL_EXPIRED = "deal.expired";

    private final RabbitTemplate rabbitTemplate;

    public void publishDealCreated(Deal deal) {
        send(DEAL_CREATED, new DealCreatedEvent(
                UUID.randomUUID().toString(),
                DEAL_CREATED,
                occurredAt(),
                deal.getId(),
                deal.getCreatorId(),
                deal.getCategory(),
                deal.getDealName(),
                toDouble(deal.getLatitude()),
                toDouble(deal.getLongitude())));
    }

    /** The host cancelled; {@code participantIds} are the users being refunded. */
    public void publishDealCancelled(Deal deal, List<String> participantIds) {
        send(DEAL_CANCELLED, closedEvent(DEAL_CANCELLED, deal, participantIds));
    }

    /** The deal ran past expires_at; {@code participantIds} are the users being refunded. */
    public void publishDealExpired(Deal deal, List<String> participantIds) {
        send(DEAL_EXPIRED, closedEvent(DEAL_EXPIRED, deal, participantIds));
    }

    private static DealClosedEvent closedEvent(String eventType, Deal deal, List<String> participantIds) {
        return new DealClosedEvent(
                UUID.randomUUID().toString(),
                eventType,
                occurredAt(),
                deal.getId(),
                deal.getCreatorId(),
                deal.getCategory(),
                deal.getDealName(),
                toDouble(deal.getLatitude()),
                toDouble(deal.getLongitude()),
                List.copyOf(participantIds));
    }

    private void send(String routingKey, Object event) {
        String eventId = event instanceof DealClosedEvent closed ? closed.eventId() : ((DealCreatedEvent) event).eventId();
        rabbitTemplate.convertAndSend(RabbitMqConfig.EVENTS_EXCHANGE, routingKey, event, message -> {
            MessageProperties properties = message.getMessageProperties();
            properties.setMessageId(eventId);
            properties.setType(routingKey);
            properties.setTimestamp(new Date());
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return message;
        });
    }

    private static String occurredAt() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
