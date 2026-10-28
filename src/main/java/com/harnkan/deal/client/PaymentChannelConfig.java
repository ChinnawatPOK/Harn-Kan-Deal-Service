package com.harnkan.deal.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.harnkan.deal.grpc.payment.PaymentServiceGrpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;

@Configuration
public class PaymentChannelConfig {

    private ManagedChannel channel;

    @Bean
    public ManagedChannel paymentServiceChannel(
            @Value("${payment.service.host:localhost}") String host,
            @Value("${payment.service.port:9091}") int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext() // internal service-to-service traffic; no TLS for the demo
                .build();
        return channel;
    }

    @Bean
    public PaymentServiceGrpc.PaymentServiceBlockingStub paymentServiceBlockingStub(ManagedChannel paymentServiceChannel) {
        return PaymentServiceGrpc.newBlockingStub(paymentServiceChannel);
    }

    @PreDestroy
    public void shutdown() {
        if (channel != null) {
            channel.shutdown();
        }
    }
}
