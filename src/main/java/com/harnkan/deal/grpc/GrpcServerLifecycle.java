package com.harnkan.deal.grpc;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import lombok.extern.slf4j.Slf4j;

/**
 * Starts/stops the gRPC server alongside the Spring application context.
 *
 * We wire this by hand (instead of the net.devh grpc-spring-boot-starter) because that
 * starter does not yet publish a build compatible with this project's Spring Boot version;
 * a plain {@link SmartLifecycle} bean is enough for what we need here.
 */
@Slf4j
@Component
public class GrpcServerLifecycle implements SmartLifecycle {

    private final int port;
    private final BindableService dealGrpcService;
    private Server server;
    private volatile boolean running = false;

    public GrpcServerLifecycle(@Value("${grpc.server.port:9090}") int port, DealGrpcService dealGrpcService) {
        this.port = port;
        this.dealGrpcService = dealGrpcService;
    }

    @Override
    public void start() {
        try {
            this.server = ServerBuilder.forPort(port)
                    .addService(dealGrpcService)
                    .build()
                    .start();
            this.running = true;
            log.info("gRPC server started on port {}", port);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start gRPC server on port " + port, e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.shutdown();
            try {
                if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        this.running = false;
        log.info("gRPC server stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // start after the JPA/DataSource infrastructure is up
        return Integer.MAX_VALUE - 1;
    }
}
