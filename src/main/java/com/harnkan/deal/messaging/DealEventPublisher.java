package com.harnkan.deal.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
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

    private final RabbitTemplate rabbitTemplate;

    public void publishDealCreated(Deal deal) {
        DealCreatedEvent event = new DealCreatedEvent(
                UUID.randomUUID().toString(),
                DEAL_CREATED,
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
                deal.getId(),
                deal.getCreatorId(),
                deal.getCategory(),
                deal.getShopName(),
                toDouble(deal.getLatitude()),
                toDouble(deal.getLongitude()));

        rabbitTemplate.convertAndSend(RabbitMqConfig.EVENTS_EXCHANGE, DEAL_CREATED, event, message -> {
            MessageProperties properties = message.getMessageProperties();
            properties.setMessageId(event.eventId());
            properties.setType(DEAL_CREATED);
            properties.setTimestamp(new Date());
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return message;
        });
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
