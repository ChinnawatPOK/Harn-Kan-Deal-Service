package com.harnkan.deal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Boots the whole application (real wiring, real JPQL, real JSON) on in-memory H2 instead of MySQL,
 * with the gRPC server on a free port and Payment / RabbitMQ unreachable on purpose.
 * Flyway is off here, so the V2 migration itself is NOT exercised by this test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:smoke;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.show-sql=false",
        "grpc.server.port=0",
        "payment.service.port=1",
        "spring.rabbitmq.port=1",
        "deal.scheduler.enabled=false"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DealEndpointsH2Test {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static String dealId;

    @Value("${local.server.port}")
    int port;

    private HttpResponse<String> call(String method, String path, String userId, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (userId != null) {
            request.header("X-User-Id", userId).header("X-User-Name", "Ploy%20S.");
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Order(1)
    void createDeal_returns201DealDetail() throws Exception {
        HttpResponse<String> response = call("POST", "/api/deals", "host-1", """
                {"dealName":"Pizza","category":"FOOD","pickupLocation":"Ari","latitude":13.7798,"longitude":100.5446,
                 "details":"x","totalPrice":760,"targetSlots":3,"expiresInMinutes":30}""");

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode deal = JSON.readTree(response.body());
        dealId = deal.get("id").asString();
        assertThat(dealId).hasSize(32);
        assertThat(deal.get("pricePerSlot").decimalValue()).isEqualByComparingTo("254.00");
        assertThat(deal.get("joinedSlots").asInt()).isEqualTo(1);
        assertThat(deal.get("expiresAt").asString()).endsWith("Z");
        assertThat(deal.get("participants")).hasSize(1);
        assertThat(deal.get("participants").get(0).get("role").asString()).isEqualTo("HOST");
        assertThat(deal.get("participants").get(0).get("name").asString()).isEqualTo("Ploy S.");
        assertThat(deal.get("me").get("actions").get("canCancel").asBoolean()).isTrue();
    }

    @Test
    @Order(2)
    void nearbyFindsItWithinRadiusOnly() throws Exception {
        JsonNode near = JSON.readTree(call("GET", "/api/deals/nearby?lat=13.78&lng=100.54", "u2", null).body());
        JsonNode far = JSON.readTree(call("GET", "/api/deals/nearby?lat=14.5&lng=101.5", "u2", null).body());

        assertThat(near.get("total").asInt()).isEqualTo(1);
        assertThat(near.get("items").get(0).get("distanceMeters").asInt()).isBetween(100, 800);
        assertThat(near.get("items").get(0).get("participantInitials").get(0).asString()).isEqualTo("PS");
        assertThat(far.get("total").asInt()).isZero();
    }

    @Test
    @Order(3)
    void detailForAStrangerHasNoMeAndCanJoin() throws Exception {
        JsonNode deal = JSON.readTree(call("GET", "/api/deals/" + dealId + "?lat=13.78&lng=100.54", "u2", null).body());

        assertThat(deal.get("me").isNull()).isTrue();
        assertThat(deal.get("actionsIfNotJoined").get("canJoin").asBoolean()).isTrue();
        assertThat(deal.get("distanceMeters").isNumber()).isTrue();
    }

    @Test
    @Order(4)
    void mineListsItUnderCreated() throws Exception {
        JsonNode created = JSON.readTree(call("GET", "/api/deals/mine?tab=created", "host-1", null).body());
        JsonNode joined = JSON.readTree(call("GET", "/api/deals/mine?tab=joined", "host-1", null).body());

        assertThat(created.get("total").asInt()).isEqualTo(1);
        assertThat(created.get("items").get(0).get("myRole").asString()).isEqualTo("HOST");
        assertThat(created.get("items").get(0).get("readySlots").asInt()).isEqualTo(1);
        assertThat(joined.get("total").asInt()).isZero();
    }

    @Test
    @Order(5)
    void joinWhenPaymentIsDownIs502AndChangesNothing() throws Exception {
        HttpResponse<String> response = call("POST", "/api/deals/" + dealId + "/join", "u2", null);

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(JSON.readTree(response.body()).get("code").asString()).isEqualTo("PAYMENT_SERVICE_UNAVAILABLE");
        JsonNode deal = JSON.readTree(call("GET", "/api/deals/" + dealId, "u2", null).body());
        assertThat(deal.get("joinedSlots").asInt()).isEqualTo(1);
        assertThat(deal.get("me").isNull()).isTrue();
    }

    @Test
    @Order(6)
    void hostCannotJoinAndMissingUserIs401() throws Exception {
        assertThat(call("POST", "/api/deals/" + dealId + "/join", "host-1", null).statusCode()).isEqualTo(409);
        assertThat(call("GET", "/api/deals/" + dealId, null, null).statusCode()).isEqualTo(401);
    }

    @Test
    @Order(7)
    void onlyTheHostCancels_andCancelStillSucceedsWhenPaymentIsDown() throws Exception {
        assertThat(call("DELETE", "/api/deals/" + dealId, "u2", null).statusCode()).isEqualTo(403);

        HttpResponse<String> response = call("DELETE", "/api/deals/" + dealId, "host-1", null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode deal = JSON.readTree(response.body());
        assertThat(deal.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(deal.get("cancelledAt").asString()).endsWith("Z");
        assertThat(deal.get("me").get("actions").get("canCancel").asBoolean()).isFalse();
        // gone from Explore, now under History
        assertThat(JSON.readTree(call("GET", "/api/deals/nearby?lat=13.78&lng=100.54", "u2", null).body()).get("total").asInt()).isZero();
        assertThat(JSON.readTree(call("GET", "/api/deals/mine?tab=history", "host-1", null).body()).get("total").asInt()).isEqualTo(1);
    }

    @Test
    @Order(8)
    void validationErrorsUseTheCommonShape() throws Exception {
        HttpResponse<String> response = call("POST", "/api/deals", "host-1", """
                {"dealName":"Pizza","category":"FOOD","pickupLocation":"Ari","latitude":13.7,"longitude":100.5,
                 "totalPrice":760,"targetSlots":1,"expiresInMinutes":30}""");

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode error = JSON.readTree(response.body());
        assertThat(error.get("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.get("timestamp").asString()).endsWith("Z");
        assertThat(call("GET", "/api/deals/nope", "u1", null).statusCode()).isEqualTo(404);
    }
}
