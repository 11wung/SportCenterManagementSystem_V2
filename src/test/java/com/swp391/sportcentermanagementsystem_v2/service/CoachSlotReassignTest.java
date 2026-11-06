package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.entity.Order;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Coach nghỉ việc, suất 1-1 đã đặt được chuyển sang coach khác: giá chung nên giá, đơn không đổi và không hoàn gì
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CoachSlotReassignTest {

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
    NotificationService notificationService;

    @InjectMocks
    CoachSlotService service;

    private CoachSlot slot;
    private Order order;

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

    @BeforeEach
    void setUp() {
        User manager = user(1L, "MANAGER");
        User oldCoach = user(10L, "COACH");
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
        slot.setPrice(new BigDecimal("300000"));

        // Member được giảm 20%: giá gốc 300.000, đã trả 240.000
        order = new Order();
        order.setId(50L);
        order.setCoachSlot(slot);
        order.setListPrice(new BigDecimal("300000"));
        order.setDiscountKind("TIER");
        order.setDiscountAmount(new BigDecimal("60000"));
        order.setTotalAmount(new BigDecimal("240000"));

        when(userRepository.findByEmail(manager.getEmail())).thenReturn(Optional.of(manager));
        when(userRepository.findById(10L)).thenReturn(Optional.of(oldCoach));
        // Coach id 12 đứng trước 11 trong danh sách, nhưng người thay phải là id nhỏ nhất còn rảnh
        when(userRepository.findByRole_CodeAndIsActiveTrue("COACH"))
                .thenReturn(List.of(oldCoach, user(12L, "COACH"), user(11L, "COACH")));
        when(slotRepository.findByCoach_IdAndStatusInAndEndAtAfterOrderByStartAt(eq(10L), anyList(), any()))
                .thenReturn(List.of(slot));
        when(slotRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(slot));
        when(slotRepository.saveAndFlush(any(CoachSlot.class))).thenAnswer(inv -> inv.getArgument(0));
        when(slotRepository.findCoachSessionConflict(anyLong(), any(), any(), anyInt())).thenReturn(Optional.empty());
        when(slotRepository.findCoachOverlapping(anyLong(), any(), any(), any())).thenReturn(List.of());
        when(settings.getInt("schedule.buffer_minutes")).thenReturn(15);
        when(orderRepository.findByCoachSlot_Id(200L)).thenReturn(Optional.of(order));
    }

    @Test
    void bookedSessionMovesToTheNextFreeCoachWithoutAnyRefund() {
        Map<String, Object> result = service.cancelAllForResignedCoach(10L, "u1@example.com", "resigned");

        assertEquals(1, result.get("reassignedBookings"));
        assertEquals(0, result.get("canceledBookings"));
        assertEquals(0, ((BigDecimal) result.get("refundTotal")).signum());
        verify(refundService, never()).createPartialRefund(any(), any(), any());
        verify(refundService, never()).createRefund(any(), any(), any());
        // Đơn đi theo dòng suất mới của coach thay thế
        assertEquals(11L, order.getCoachSlot().getCoach().getId());
        assertEquals(new BigDecimal("300000"), order.getCoachSlot().getPrice());
    }

    @Test
    void orderAmountsStayUntouched() {
        service.cancelAllForResignedCoach(10L, "u1@example.com", "resigned");

        assertEquals(new BigDecimal("300000"), order.getListPrice());
        assertEquals(new BigDecimal("60000"), order.getDiscountAmount());
        assertEquals(new BigDecimal("240000"), order.getTotalAmount());
    }

    @Test
    void paidRequestSessionWithoutAReplacementWaitsForRescheduleInsteadOfRefunding() {
        slot.setRequest(new CoachSlotRequest());
        // Không coach nào rảnh đúng giờ
        when(slotRepository.findCoachSessionConflict(anyLong(), any(), any(), anyInt())).thenReturn(Optional.of("busy class"));

        Map<String, Object> result = service.cancelAllForResignedCoach(10L, "u1@example.com", "resigned");

        assertEquals(1, result.get("pendingReschedule"));
        assertEquals(0, result.get("canceledBookings"));
        assertTrue(slot.isNeedsReschedule());
        assertEquals("BOOKED", slot.getStatus());
        verify(refundService, never()).createRefund(any(), any(), any());
    }
}
