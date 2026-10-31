package com.harnkan.deal.web;

import static com.harnkan.deal.TestData.NOW;
import static com.harnkan.deal.TestData.deal;
import static com.harnkan.deal.TestData.participant;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

import com.harnkan.deal.controller.DealController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.harnkan.deal.domain.Deal;
import com.harnkan.deal.domain.DealStatus;
import com.harnkan.deal.domain.ParticipantStatus;
import com.harnkan.deal.exception.DealStateException;
import com.harnkan.deal.exception.PaymentServiceException;
import com.harnkan.deal.service.CreateDealCommand;
import com.harnkan.deal.service.DealQueryService;
import com.harnkan.deal.service.DealService;
import com.harnkan.deal.service.JoinDealResult;
import com.harnkan.deal.dto.ActionsDto;
import com.harnkan.deal.dto.DealDetailDto;
import com.harnkan.deal.dto.JoinOnlyActionsDto;
import com.harnkan.deal.dto.MeDto;
import com.harnkan.deal.dto.PageDto;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/** Gateway-mode behaviour: the caller is whoever X-User-Id says, and the body never names a user. */
@ExtendWith(MockitoExtension.class)
class DealControllerTest {

    @Mock
    private DealService dealService;
    @Mock
    private DealQueryService queryService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new DealController(dealService, queryService, Clock.fixed(NOW, ZoneOffset.UTC)))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static DealDetailDto detail(String id, MeDto me) {
        return new DealDetailDto(id, "Pizza", null, "FOOD", null, "Ari", new BigDecimal("13.7"), new BigDecimal("100.5"),
                new BigDecimal("760.00"), new BigDecimal("254.00"), 3, 1, "ACTIVE", NOW.plusSeconds(1800), null, null,
                NOW, null, List.of(), me, new JoinOnlyActionsDto(true));
    }

    private static final String CREATE_BODY = """
            {"dealName":"Pizza","category":"FOOD","pickupLocation":"Ari","latitude":13.7798,"longitude":100.5446,
             "details":"x","totalPrice":760,"targetSlots":3,"expiresInMinutes":30,"creatorId":"someone-else"}""";

    @Test
    void create_usesGatewayIdentityAndIgnoresBody() throws Exception {
        Deal saved = deal("d1", DealStatus.ACTIVE, 1, 3);
        when(dealService.createDeal(any())).thenReturn(saved);
        when(queryService.detail("d1", "host-1", null, null)).thenReturn(detail("d1", null));

        mvc.perform(post("/api/deals").header("X-User-Id", "host-1").header("X-User-Name", "Ploy S.")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("d1"))
                .andExpect(jsonPath("$.pricePerSlot").value(254.00))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-24T08:30:00Z"));

        ArgumentCaptor<CreateDealCommand> cmd = ArgumentCaptor.forClass(CreateDealCommand.class);
        verify(dealService).createDeal(cmd.capture());
        org.assertj.core.api.Assertions.assertThat(cmd.getValue().getCreatorId()).isEqualTo("host-1");
        org.assertj.core.api.Assertions.assertThat(cmd.getValue().getCreatorName()).isEqualTo("Ploy S.");
        org.assertj.core.api.Assertions.assertThat(cmd.getValue().getExpiresAt()).isEqualTo(NOW.plusSeconds(1800));
    }

    @Test
    void percentEncodedNameFromGatewayIsDecoded() throws Exception {
        when(dealService.createDeal(any())).thenReturn(deal("d1", DealStatus.ACTIVE, 1, 3));
        when(queryService.detail(any(), any(), any(), any())).thenReturn(detail("d1", null));

        mvc.perform(post("/api/deals").header("X-User-Id", "host-1")
                .header("X-User-Name", "%E0%B8%9E%E0%B8%A5%E0%B8%AD%E0%B8%A2%20%E0%B8%AA.")
                .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY)).andExpect(status().isCreated());

        ArgumentCaptor<CreateDealCommand> cmd = ArgumentCaptor.forClass(CreateDealCommand.class);
        verify(dealService).createDeal(cmd.capture());
        org.assertj.core.api.Assertions.assertThat(cmd.getValue().getCreatorName()).isEqualTo("พลอย ส.");
    }

    @Test
    void userIdLongerThanTheColumn_is401NotADatabaseError() throws Exception {
        mvc.perform(post("/api/deals").header("X-User-Id", "a".repeat(65))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void uuidUserIdIsAccepted() throws Exception {
        when(dealService.createDeal(any())).thenReturn(deal("d1", DealStatus.ACTIVE, 1, 3));
        when(queryService.detail(any(), any(), any(), any())).thenReturn(detail("d1", null));

        mvc.perform(post("/api/deals").header("X-User-Id", "0a959523-cfdb-4f97-bc80-e78b935d82c9")
                .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY)).andExpect(status().isCreated());

        ArgumentCaptor<CreateDealCommand> cmd = ArgumentCaptor.forClass(CreateDealCommand.class);
        verify(dealService).createDeal(cmd.capture());
        org.assertj.core.api.Assertions.assertThat(cmd.getValue().getCreatorId())
                .isEqualTo("0a959523-cfdb-4f97-bc80-e78b935d82c9");
    }

    @Test
    void veryLongNameIsCutToTheColumnSize() throws Exception {
        when(dealService.createDeal(any())).thenReturn(deal("d1", DealStatus.ACTIVE, 1, 3));
        when(queryService.detail(any(), any(), any(), any())).thenReturn(detail("d1", null));

        mvc.perform(post("/api/deals").header("X-User-Id", "host-1").header("X-User-Name", "a".repeat(300))
                .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY)).andExpect(status().isCreated());

        ArgumentCaptor<CreateDealCommand> cmd = ArgumentCaptor.forClass(CreateDealCommand.class);
        verify(dealService).createDeal(cmd.capture());
        org.assertj.core.api.Assertions.assertThat(cmd.getValue().getCreatorName()).hasSize(100);
    }

    @Test
    void missingUserHeader_is401() throws Exception {
        mvc.perform(post("/api/deals").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void missingNameFallsBackToShortId() throws Exception {
        when(dealService.createDeal(any())).thenReturn(deal("d1", DealStatus.ACTIVE, 1, 3));
        when(queryService.detail(any(), any(), any(), any())).thenReturn(detail("d1", null));

        mvc.perform(post("/api/deals").header("X-User-Id", "6aa12c60a64bc2e2be695ec1")
                .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY)).andExpect(status().isCreated());

        ArgumentCaptor<CreateDealCommand> cmd = ArgumentCaptor.forClass(CreateDealCommand.class);
        verify(dealService).createDeal(cmd.capture());
        org.assertj.core.api.Assertions.assertThat(cmd.getValue().getCreatorName()).isEqualTo("User 5ec1");
    }

    @Test
    void create_expiresInMinutesOutOfRange_is400() throws Exception {
        mvc.perform(post("/api/deals").header("X-User-Id", "host-1").contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("\"expiresInMinutes\":30", "\"expiresInMinutes\":2")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void nearby_requiresCoordinates() throws Exception {
        mvc.perform(get("/api/deals/nearby").header("X-User-Id", "u1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void nearby_passesQueryParametersWithDefaults() throws Exception {
        when(queryService.nearby(13.78, 100.54, 500, "FOOD", "pizza", 0, 20))
                .thenReturn(new PageDto<>(List.of(), 0, 20, 0));

        mvc.perform(get("/api/deals/nearby?lat=13.78&lng=100.54&category=FOOD&q=pizza").header("X-User-Id", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void join_returns201WithPaymentIdAndDeal() throws Exception {
        Deal d = deal("d1", DealStatus.ACTIVE, 2, 3);
        when(dealService.joinDeal(eq("d1"), any())).thenReturn(
                new JoinDealResult(d, participant("p2", "d1", "anya", ParticipantStatus.RESERVED)));
        MeDto me = new MeDto("PARTICIPANT", "RESERVED", "pay-anya", new BigDecimal("254.00"),
                new BigDecimal("0.00"), NOW.plusSeconds(300), new ActionsDto(false, true, true, false, false));
        when(queryService.detail("d1", "anya", null, null)).thenReturn(detail("d1", me));

        mvc.perform(post("/api/deals/d1/join").header("X-User-Id", "anya"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").value("pay-anya"))
                .andExpect(jsonPath("$.amountToPay").value(254.00))
                .andExpect(jsonPath("$.reservedUntil").value("2026-10-24T08:05:00Z"))
                .andExpect(jsonPath("$.deal.me.actions.canPay").value(true));
    }

    @Test
    void join_conflictCarriesTheServiceErrorCode() throws Exception {
        when(dealService.joinDeal(eq("d1"), any())).thenThrow(new DealStateException("DEAL_FULL", "Deal is already full"));

        mvc.perform(post("/api/deals/d1/join").header("X-User-Id", "anya"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEAL_FULL"));
    }

    @Test
    void join_paymentAlreadyExists_is409AlreadyJoined() throws Exception {
        when(dealService.joinDeal(eq("d1"), any())).thenThrow(
                new PaymentServiceException("dup", new StatusRuntimeException(Status.ALREADY_EXISTS)));

        mvc.perform(post("/api/deals/d1/join").header("X-User-Id", "anya"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_JOINED"));
    }

    @Test
    void join_paymentDown_is502() throws Exception {
        when(dealService.joinDeal(eq("d1"), any())).thenThrow(
                new PaymentServiceException("down", new StatusRuntimeException(Status.UNAVAILABLE)));

        mvc.perform(post("/api/deals/d1/join").header("X-User-Id", "anya"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PAYMENT_SERVICE_UNAVAILABLE"));
    }

    @Test
    void leaveAndCancel_returnDealDetail() throws Exception {
        when(queryService.detail("d1", "u1", null, null)).thenReturn(detail("d1", null));

        mvc.perform(post("/api/deals/d1/leave").header("X-User-Id", "u1")).andExpect(status().isOk());
        mvc.perform(delete("/api/deals/d1").header("X-User-Id", "u1")).andExpect(status().isOk());

        verify(dealService).leaveDeal(eq("d1"), any());
        verify(dealService).cancelDeal("d1", "u1");
    }

    @Test
    void mine_defaultsToJoinedTab() throws Exception {
        when(queryService.mine("u1", "joined", null, null, 0, 20)).thenReturn(new PageDto<>(List.of(), 0, 20, 0));

        mvc.perform(get("/api/deals/mine").header("X-User-Id", "u1")).andExpect(status().isOk());
    }

    @Test
    void getDeal_passesViewerLocation() throws Exception {
        when(queryService.detail("d1", "u1", 13.78, 100.54)).thenReturn(detail("d1", null));

        mvc.perform(get("/api/deals/d1?lat=13.78&lng=100.54").header("X-User-Id", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.me").doesNotExist());
    }

    @Test
    void confirmReceipt_returnsTheDealDetail() throws Exception {
        when(queryService.detail("d1", "anya", null, null)).thenReturn(detail("d1", null));

        mvc.perform(post("/api/deals/d1/confirm-receipt").header("X-User-Id", "anya"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("d1"));
        verify(dealService).confirmReceipt(eq("d1"), any(CurrentUser.class));
    }

    @Test
    void confirmReceipt_beforeEveryonePaid_is409DealNotReady() throws Exception {
        when(dealService.confirmReceipt(eq("d1"), any(CurrentUser.class)))
                .thenThrow(new DealStateException("DEAL_NOT_READY", "Everyone must pay first"));

        mvc.perform(post("/api/deals/d1/confirm-receipt").header("X-User-Id", "anya"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEAL_NOT_READY"));
    }

    @Test
    void receipt_returnsTheReceipt() throws Exception {
        when(queryService.receipt("d1", "anya")).thenReturn(new com.harnkan.deal.dto.ReceiptDto("RC-D1-ANYA", "d1", "Pizza",
                "FOOD", NOW, new BigDecimal("756.00"), new BigDecimal("189.00"), "PARTICIPANT", new BigDecimal("189.00"),
                "pay-anya", new BigDecimal("567.00"),
                List.of(new com.harnkan.deal.dto.ReceiptLineDto("You", "PARTICIPANT", new BigDecimal("189.00")))));

        mvc.perform(get("/api/deals/d1/receipt").header("X-User-Id", "anya"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptNo").value("RC-D1-ANYA"))
                .andExpect(jsonPath("$.payoutToHost").value(567.00))
                .andExpect(jsonPath("$.lines[0].name").value("You"));
    }
}
