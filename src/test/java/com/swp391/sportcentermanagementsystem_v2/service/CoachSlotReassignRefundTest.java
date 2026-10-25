package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.CoachProfile;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.Order;
import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import com.swp391.sportcentermanagementsystem_v2.entity.Refund;
import com.swp391.sportcentermanagementsystem_v2.entity.Role;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachProfileRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.OrderRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.PaymentRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Coach nghỉ việc, suất 1-1 đã đặt được chuyển sang coach hạng thấp hơn: hoàn theo tỷ lệ số member đã trả
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CoachSlotReassignRefundTest {

    @Mock
    CoachSlotRepository slotRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    CoachProfileRepository coachProfileRepository;
    @Mock
    PaymentRepository paymentRepository;
    @Mock
    OrderRepository orderRepository;
    @Mock
    RefundService refundService;
    @Mock
    AuditService auditService;
    @Mock
    SystemSettingService settings;
    @Mock
    CenterHoursService centerHours;
    @Mock
    VoucherService voucherService;
    @Mock
    NotificationService notificationService;

    @InjectMocks
    CoachSlotService service;

    private CoachSlot slot;
    private Order order;
    private Payment payment;

    private static User user(long id, String role) {
        Role r = new Role();
        r.setCode(role);
        User u = new User();
        u.setId(id);
        u.setRole(r);
        u.setIsActive(true);
        u.setEmail("u" + id + "@example.com");
        u.setFullName("User " + id);
        return u;
    }

    private static CoachProfile profile(User coach, String rank) {
        CoachProfile p = new CoachProfile();
        p.setUser(coach);
        p.setCoachRank(rank);
        return p;
    }

    @BeforeEach
    void setUp() {
        User manager = user(1L, "MANAGER");
        User oldCoach = user(10L, "COACH");
        User newCoach = user(11L, "COACH");
        User member = user(5L, "MEMBER");

        OffsetDateTime start = OffsetDateTime.now().plusDays(2);
        slot = new CoachSlot();
        slot.setId(200L);
        slot.setCoach(oldCoach);
        slot.setMember(member);
        slot.setStatus("BOOKED");
        slot.setKind("ONE_ON_ONE");
        slot.setStartAt(start);
        slot.setEndAt(start.plusMinutes(60));
        slot.setPrice(new BigDecimal("650000"));

        // Member được giảm 20%: giá gốc 650.000, đã trả 520.000
        order = new Order();
        order.setId(50L);
        order.setCoachSlot(slot);
        order.setListPrice(new BigDecimal("650000"));
        order.setDiscountKind("TIER");
        order.setDiscountAmount(new BigDecimal("130000"));
        order.setTotalAmount(new BigDecimal("520000"));
        payment = new Payment();
        payment.setId(70L);
        payment.setAmount(new BigDecimal("520000"));

        when(userRepository.findByEmail(manager.getEmail())).thenReturn(Optional.of(manager));
        when(userRepository.findById(10L)).thenReturn(Optional.of(oldCoach));
        when(userRepository.findByRole_CodeAndIsActiveTrue("COACH")).thenReturn(List.of(oldCoach, newCoach));
        when(coachProfileRepository.findAllById(anyList())).thenReturn(List.of(profile(newCoach, "COACH")));
        when(coachProfileRepository.findById(10L)).thenReturn(Optional.of(profile(oldCoach, "MASTER")));
        when(slotRepository.findByCoach_IdAndStatusInAndEndAtAfterOrderByStartAt(eq(10L), anyList(), any()))
                .thenReturn(List.of(slot));
        when(slotRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(slot));
        when(slotRepository.saveAndFlush(any(CoachSlot.class))).thenAnswer(inv -> inv.getArgument(0));
        when(slotRepository.findCoachSessionConflict(anyLong(), any(), any(), anyInt())).thenReturn(Optional.empty());
        when(slotRepository.findCoachOverlapping(anyLong(), any(), any(), any())).thenReturn(List.of());
        when(settings.getInt("schedule.buffer_minutes")).thenReturn(15);
        when(settings.getIntField("coach.rank_prices", "COACH")).thenReturn(250000);
        when(paymentRepository.findFirstByOrder_CoachSlot_IdAndStatusOrderByIdDesc(200L, "SUCCESS"))
                .thenReturn(Optional.of(payment));
        when(refundService.refundable(payment)).thenReturn(new BigDecimal("520000"));
        when(refundService.createPartialRefund(any(), any(), anyString())).thenAnswer(inv -> {
            Refund refund = new Refund();
            refund.setAmount(inv.getArgument(1));
            return refund;
        });
        when(orderRepository.findByCoachSlot_Id(200L)).thenReturn(Optional.of(order));
    }

    @Test
    void refundKeepsTheMembersDiscountRatio() {
        Map<String, Object> result = service.cancelAllForResignedCoach(10L, "u1@example.com", "resigned");

        // Phải trả ở hạng mới: 520.000 x 250.000 / 650.000 = 200.000, nên hoàn 320.000 (không phải 650.000 - 250.000 = 400.000)
        ArgumentCaptor<BigDecimal> refunded = ArgumentCaptor.forClass(BigDecimal.class);
        verify(refundService).createPartialRefund(eq(payment), refunded.capture(), anyString());
        assertEquals(new BigDecimal("320000"), refunded.getValue());
        assertEquals(new BigDecimal("320000"), result.get("refundTotal"));
    }

    @Test
    void orderIsRepricedToTheNewRankWithTheSameDiscountRatio() {
        service.cancelAllForResignedCoach(10L, "u1@example.com", "resigned");

        assertEquals(new BigDecimal("250000"), order.getListPrice());
        assertEquals(new BigDecimal("200000"), order.getTotalAmount());
        assertEquals(new BigDecimal("50000"), order.getDiscountAmount());
        assertEquals(order.getListPrice().subtract(order.getDiscountAmount()), order.getTotalAmount());
    }

    @Test
    void memberWithoutDiscountStillGetsTheFullPriceDifference() {
        order.setDiscountKind("NONE");
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setTotalAmount(new BigDecimal("650000"));
        payment.setAmount(new BigDecimal("650000"));
        when(refundService.refundable(payment)).thenReturn(new BigDecimal("650000"));

        service.cancelAllForResignedCoach(10L, "u1@example.com", "resigned");

        verify(refundService).createPartialRefund(eq(payment), eq(new BigDecimal("400000")), anyString());
        assertEquals(new BigDecimal("250000"), order.getTotalAmount());
    }

    @Test
    void freeOrderWithoutPaymentRefundsNothingAndStaysFree() {
        order.setTotalAmount(BigDecimal.ZERO);
        order.setDiscountAmount(new BigDecimal("650000"));
        when(paymentRepository.findFirstByOrder_CoachSlot_IdAndStatusOrderByIdDesc(200L, "SUCCESS"))
                .thenReturn(Optional.empty());

        service.cancelAllForResignedCoach(10L, "u1@example.com", "resigned");

        verify(refundService, never()).createPartialRefund(any(), any(), anyString());
        assertEquals(0, order.getTotalAmount().signum());
        assertEquals(new BigDecimal("250000"), order.getDiscountAmount());
    }
}
