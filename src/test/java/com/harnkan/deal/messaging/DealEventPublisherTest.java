package com.harnkan.deal.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.harnkan.deal.domain.Deal;
import com.harnkan.deal.domain.DealStatus;

/** Checks the deal.created message matches docs/notification-rabbitmq-spec.md. */
@ExtendWith(MockitoExtension.class)
class DealEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Test
    void publishDealCreated_sendsSpecPayloadToEventsExchange() {
        Deal deal = Deal.builder()
                .id("61643f760a9142c9a7918c3d868d9301")
                .creatorId("6aa12c60a64bc2e2be695ec1")
                .dealName("Coffee Corner")
                .details("Buy 1 Get 1")
                .category("DRINK")
                .pricePerSlot(new BigDecimal("49.00"))
                .targetSlots(3)
                .joinedSlots(1)
                .latitude(new BigDecimal("13.74660000"))
                .longitude(new BigDecimal("100.53470000"))
                .expiresAt(Instant.now().plusSeconds(3600))
                .status(DealStatus.ACTIVE)
                .build();

        new DealEventPublisher(rabbitTemplate).publishDealCreated(deal);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<MessagePostProcessor> postProcessor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(
                eq("harnkan.events"), eq("deal.created"), payload.capture(), postProcessor.capture());

        DealCreatedEvent event = (DealCreatedEvent) payload.getValue();
        assertThat(event.eventId()).isNotBlank();
        assertThat(event.eventType()).isEqualTo("deal.created");
        assertThat(Instant.parse(event.occurredAt())).isBeforeOrEqualTo(Instant.now());
        assertThat(event.dealId()).isEqualTo("61643f760a9142c9a7918c3d868d9301");
        assertThat(event.creatorId()).isEqualTo("6aa12c60a64bc2e2be695ec1");
        assertThat(event.category()).isEqualTo("DRINK");
        assertThat(event.shopName()).isEqualTo("Coffee Corner");
        assertThat(event.lat()).isEqualTo(13.7466);
        assertThat(event.lng()).isEqualTo(100.5347);

        Message message = postProcessor.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        MessageProperties properties = message.getMessageProperties();
        assertThat(properties.getMessageId()).isEqualTo(event.eventId());
        assertThat(properties.getType()).isEqualTo("deal.created");
        assertThat(properties.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(properties.getTimestamp()).isNotNull();
    }

    @Test
    void jsonConverter_writesSpecFieldNamesAsPlainJson() {
        DealCreatedEvent event = new DealCreatedEvent("e-1", "deal.created", "2026-10-13T08:15:30Z",
                "d-1", "c-1", "DRINK", "ชานมไข่มุก", 13.7466, 100.5347);

        Message message = new RabbitMqConfig().jsonMessageConverter().toMessage(event, new MessageProperties());
        String json = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(json)
                .contains("\"eventId\":\"e-1\"")
                .contains("\"eventType\":\"deal.created\"")
                .contains("\"occurredAt\":\"2026-10-13T08:15:30Z\"")
                .contains("\"dealId\":\"d-1\"")
                .contains("\"creatorId\":\"c-1\"")
                .contains("\"category\":\"DRINK\"")
                .contains("\"shopName\":\"ชานมไข่มุก\"")
                .contains("\"lat\":13.7466")
                .contains("\"lng\":100.5347");
    }

    @Test
    void publishDealCreated_eachEventGetsItsOwnId() {
        Deal deal = Deal.builder().id("d1").creatorId("c1").category("FOOD").dealName("s").build();
        DealEventPublisher publisher = new DealEventPublisher(rabbitTemplate);

        publisher.publishDealCreated(deal);
        publisher.publishDealCreated(deal);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, org.mockito.Mockito.times(2))
                .convertAndSend(eq("harnkan.events"), eq("deal.created"), payload.capture(), any(MessagePostProcessor.class));
        String first = ((DealCreatedEvent) payload.getAllValues().get(0)).eventId();
        String second = ((DealCreatedEvent) payload.getAllValues().get(1)).eventId();
        assertThat(first).isNotEqualTo(second);
    }
}
