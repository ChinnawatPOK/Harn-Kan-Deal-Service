package com.harnkan.deal.service;

import static com.harnkan.deal.TestData.NOW;
import static com.harnkan.deal.TestData.deal;
import static com.harnkan.deal.TestData.host;
import static com.harnkan.deal.TestData.participant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.harnkan.deal.domain.Deal;
import com.harnkan.deal.domain.DealParticipant;
import com.harnkan.deal.domain.DealStatus;
import com.harnkan.deal.domain.ParticipantStatus;
import com.harnkan.deal.exception.DealNotFoundException;
import com.harnkan.deal.exception.DealValidationException;
import com.harnkan.deal.repository.DealParticipantRepository;
import com.harnkan.deal.repository.DealRepository;
import com.harnkan.deal.dto.DealDetailDto;
import com.harnkan.deal.dto.MineItemDto;
import com.harnkan.deal.dto.NearbyItemDto;
import com.harnkan.deal.dto.PageDto;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DealQueryServiceTest {

    @Mock
    private DealRepository dealRepository;
    @Mock
    private DealParticipantRepository participantRepository;

    private DealQueryService service;

    @BeforeEach
    void setUp() {
        service = new DealQueryService(dealRepository, participantRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void detail_buildsParticipantsMeAndDistance() {
        Deal d = deal("d1", DealStatus.ACTIVE, 3, 4);
        DealParticipant narin = participant("p2", "d1", "narin", ParticipantStatus.HELD);
        DealParticipant anya = participant("p3", "d1", "anya", ParticipantStatus.RESERVED);
        DealParticipant left = participant("p4", "d1", "ghost", ParticipantStatus.FAILED);
        when(dealRepository.findById("d1")).thenReturn(Optional.of(d));
        when(participantRepository.findByDealIdOrderByJoinedAtAsc("d1")).thenReturn(List.of(narin, left, anya, host("d1")));

        DealDetailDto dto = service.detail("d1", "anya", 13.7798, 100.5446);

        assertThat(dto.participants()).extracting("slotNo", "userId")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "host-1"),
                        org.assertj.core.groups.Tuple.tuple(2, "narin"),
                        org.assertj.core.groups.Tuple.tuple(3, "anya"));
        assertThat(dto.distanceMeters()).isZero();
        assertThat(dto.me().status()).isEqualTo("RESERVED");
        assertThat(dto.me().amountToPay()).isEqualByComparingTo("189.00");
        assertThat(dto.me().refundAmount()).isEqualByComparingTo("0.00");
        assertThat(dto.me().actions().canPay()).isTrue();
        assertThat(dto.pricePerSlot().scale()).isEqualTo(2);
        assertThat(dto.actionsIfNotJoined().canJoin()).isTrue();
    }

    @Test
    void detail_meIsNullForOutsiderAndDistanceNullWithoutCoordinates() {
        when(dealRepository.findById("d1")).thenReturn(Optional.of(deal("d1", DealStatus.ACTIVE, 1, 4)));
        when(participantRepository.findByDealIdOrderByJoinedAtAsc("d1")).thenReturn(List.of(host("d1")));

        DealDetailDto dto = service.detail("d1", "stranger", null, null);

        assertThat(dto.me()).isNull();
        assertThat(dto.distanceMeters()).isNull();
    }

    @Test
    void detail_hostHasNothingToPay_andCancelledDealShowsRefundStatuses() {
        Deal d = deal("d1", DealStatus.CANCELLED, 3, 3);
        DealParticipant refunding = participant("p2", "d1", "narin", ParticipantStatus.REFUND_PENDING);
        when(dealRepository.findById("d1")).thenReturn(Optional.of(d));
        when(participantRepository.findByDealIdOrderByJoinedAtAsc("d1")).thenReturn(List.of(host("d1"), refunding));

        DealDetailDto asHost = service.detail("d1", "host-1", null, null);
        DealDetailDto asNarin = service.detail("d1", "narin", null, null);

        assertThat(asHost.me().amountToPay()).isEqualByComparingTo("0.00");
        assertThat(asHost.participants()).hasSize(2);
        assertThat(asNarin.me().refundAmount()).isEqualByComparingTo("189.00");
        assertThat(asNarin.actionsIfNotJoined().canJoin()).isFalse();
    }

    @Test
    void detail_unknownDeal() {
        when(dealRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail("nope", "u", null, null)).isInstanceOf(DealNotFoundException.class);
    }

    @Test
    void nearby_filtersByRadiusCategoryAndTextAndSortsByDistance() {
        Deal near = deal("near", DealStatus.ACTIVE, 2, 4);
        Deal far = deal("far", DealStatus.ACTIVE, 2, 4);
        far.setLatitude(new java.math.BigDecimal("13.78300000")); // ~ 330 m north of the search point
        Deal drink = deal("drink", DealStatus.ACTIVE, 2, 4);
        drink.setCategory("DRINK");
        Deal tooFar = deal("toofar", DealStatus.ACTIVE, 2, 4);
        tooFar.setLatitude(new java.math.BigDecimal("13.79500000")); // ~ 1.6 km
        when(dealRepository.findOpenInBox(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(far, near, drink, tooFar));
        when(participantRepository.findByDealIdIn(anyCollection())).thenReturn(List.of(host("near")));

        PageDto<NearbyItemDto> page = service.nearby(13.7798, 100.5446, 500, "FOOD", "katsu", 0, 20);

        assertThat(page.items()).extracting(NearbyItemDto::id).containsExactly("near", "far");
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items().get(0).distanceMeters()).isZero();
        assertThat(page.items().get(1).distanceMeters()).isBetween(300, 360);
    }

    @Test
    void nearby_paginates() {
        Deal a = deal("a", DealStatus.ACTIVE, 2, 4);
        Deal b = deal("b", DealStatus.ACTIVE, 2, 4);
        b.setLatitude(new java.math.BigDecimal("13.78000000"));
        when(dealRepository.findOpenInBox(any(), any(), any(), any(), any(), any())).thenReturn(List.of(a, b));

        assertThat(service.nearby(13.7798, 100.5446, 500, null, null, 0, 1).items()).hasSize(1);
        assertThat(service.nearby(13.7798, 100.5446, 500, null, null, 1, 1).items()).extracting(NearbyItemDto::id).containsExactly("b");
        assertThat(service.nearby(13.7798, 100.5446, 500, null, null, 5, 1).items()).isEmpty();
    }

    @Test
    void nearby_rejectsBadCoordinates() {
        assertThatThrownBy(() -> service.nearby(120, 0, 500, null, null, 0, 20)).isInstanceOf(DealValidationException.class);
    }

    private void givenMyDeals() {
        Deal created = deal("c", DealStatus.LOCKED, 3, 3);
        created.setCreatorId("me");
        Deal joined = deal("j", DealStatus.LOCKED, 3, 3);
        Deal cancelled = deal("x", DealStatus.CANCELLED, 3, 3);
        Deal left = deal("l", DealStatus.ACTIVE, 1, 3);

        DealParticipant myHost = host("c");
        myHost.setUserId("me");
        DealParticipant myJoin = participant("pj", "j", "me", ParticipantStatus.HELD);
        DealParticipant myRefund = participant("px", "x", "me", ParticipantStatus.REFUNDED);
        DealParticipant myLeft = participant("pl", "l", "me", ParticipantStatus.FAILED);
        when(participantRepository.findByUserId("me")).thenReturn(List.of(myHost, myJoin, myRefund, myLeft));
        when(dealRepository.findAllById(any())).thenReturn(List.of(created, joined, cancelled, left));

        DealParticipant paid = participant("p9", "c", "narin", ParticipantStatus.HELD);
        DealParticipant paid2 = participant("p10", "c", "anya", ParticipantStatus.HELD);
        when(participantRepository.findByDealIdIn(anyCollection())).thenAnswer(i -> List.of(myHost, paid, paid2, myJoin));
    }

    @Test
    void mine_createdTabShowsReadyToOrder() {
        givenMyDeals();

        PageDto<MineItemDto> page = service.mine("me", "created", null, null, 0, 20);

        assertThat(page.items()).hasSize(1);
        MineItemDto item = page.items().get(0);
        assertThat(item.id()).isEqualTo("c");
        assertThat(item.myRole()).isEqualTo("HOST");
        assertThat(item.readySlots()).isEqualTo(3);
        assertThat(item.readyToOrder()).isTrue();
    }

    @Test
    void mine_readyToOrderCountsParticipantsWhoAlreadyConfirmedReceipt() {
        givenMyDeals();
        DealParticipant myHost = host("c");
        myHost.setUserId("me");
        DealParticipant paid = participant("p9", "c", "narin", ParticipantStatus.HELD);
        DealParticipant received = participant("p10", "c", "anya", ParticipantStatus.RECEIVED);
        when(participantRepository.findByDealIdIn(anyCollection())).thenReturn(List.of(myHost, paid, received));

        MineItemDto item = service.mine("me", "created", null, null, 0, 20).items().get(0);

        assertThat(item.readyToOrder()).isTrue();
        assertThat(item.readySlots()).isEqualTo(3);
    }

    @Test
    void mine_joinedTab() {
        givenMyDeals();

        PageDto<MineItemDto> page = service.mine("me", "joined", null, null, 0, 20);

        assertThat(page.items()).extracting(MineItemDto::id).containsExactly("j");
        assertThat(page.items().get(0).myStatus()).isEqualTo("HELD");
        assertThat(page.items().get(0).readyToOrder()).isFalse();
    }

    @Test
    void mine_historyTabHasFinishedDealsAndOnesILeft_andRoleFilterWorks() {
        givenMyDeals();

        assertThat(service.mine("me", "history", null, null, 0, 20).items())
                .extracting(MineItemDto::id).containsExactlyInAnyOrder("x", "l");
        assertThat(service.mine("me", "history", null, "HOST", 0, 20).items()).isEmpty();
    }

    @Test
    void mine_rejectsUnknownTabOrRole() {
        assertThatThrownBy(() -> service.mine("me", "all", null, null, 0, 20)).isInstanceOf(DealValidationException.class);
        assertThatThrownBy(() -> service.mine("me", "joined", null, "BOSS", 0, 20)).isInstanceOf(DealValidationException.class);
    }

    // --- DR11 receipt ---------------------------------------------------------------------

    private static final String RC_DEAL = "61643f760a9142c9a7918c3d868d9301";

    private void givenCompletedDeal(DealStatus status, DealParticipant... participants) {
        Deal d = deal(RC_DEAL, status, 3, 3);
        d.setCompletedAt(NOW);
        when(dealRepository.findById(RC_DEAL)).thenReturn(Optional.of(d));
        List<DealParticipant> rows = new java.util.ArrayList<>(List.of(participants));
        rows.add(host(RC_DEAL));
        when(participantRepository.findByDealIdOrderByJoinedAtAsc(RC_DEAL)).thenReturn(rows);
    }

    @Test
    void receipt_forParticipant_showsTheirShareAndEveryoneWhoPaid() {
        givenCompletedDeal(DealStatus.COMPLETED,
                participant("p2", RC_DEAL, "narin", ParticipantStatus.RECEIVED),
                participant("p3", RC_DEAL, "anya", ParticipantStatus.RECEIVED));

        com.harnkan.deal.dto.ReceiptDto r = service.receipt(RC_DEAL, "narin");

        assertThat(r.receiptNo()).isEqualTo("RC-61643F76-ARIN");
        assertThat(r.role()).isEqualTo("PARTICIPANT");
        assertThat(r.amount()).isEqualByComparingTo("189.00");
        assertThat(r.paymentId()).isEqualTo("pay-narin");
        assertThat(r.payoutToHost()).isEqualByComparingTo("378.00");
        assertThat(r.completedAt()).isEqualTo(NOW);
        assertThat(r.lines()).extracting("name").containsExactly("You", "User anya");
    }

    @Test
    void receipt_forHost_showsThePayout() {
        givenCompletedDeal(DealStatus.COMPLETED,
                participant("p2", RC_DEAL, "narin", ParticipantStatus.RECEIVED),
                participant("p3", RC_DEAL, "anya", ParticipantStatus.RECEIVED));

        com.harnkan.deal.dto.ReceiptDto r = service.receipt(RC_DEAL, "host-1");

        assertThat(r.role()).isEqualTo("HOST");
        assertThat(r.amount()).isEqualByComparingTo("378.00");
        assertThat(r.paymentId()).isNull();
        assertThat(r.lines()).hasSize(2);
    }

    @Test
    void receipt_onlyForCompletedDeals_andOnlyForTheGroup() {
        givenCompletedDeal(DealStatus.LOCKED, participant("p2", RC_DEAL, "narin", ParticipantStatus.RECEIVED));
        assertThatThrownBy(() -> service.receipt(RC_DEAL, "narin"))
                .isInstanceOf(com.harnkan.deal.exception.DealStateException.class);

        givenCompletedDeal(DealStatus.COMPLETED,
                participant("p2", RC_DEAL, "narin", ParticipantStatus.RECEIVED),
                participant("p3", RC_DEAL, "chai", ParticipantStatus.FAILED));
        assertThatThrownBy(() -> service.receipt(RC_DEAL, "stranger"))
                .isInstanceOf(com.harnkan.deal.exception.DealPermissionException.class);
        assertThatThrownBy(() -> service.receipt(RC_DEAL, "chai"))
                .isInstanceOf(com.harnkan.deal.exception.DealPermissionException.class);
    }
}
