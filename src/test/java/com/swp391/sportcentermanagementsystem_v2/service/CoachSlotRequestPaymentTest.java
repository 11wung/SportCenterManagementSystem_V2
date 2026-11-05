package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.entity.FacilityRoom;
import com.swp391.sportcentermanagementsystem_v2.entity.Order;
import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import com.swp391.sportcentermanagementsystem_v2.entity.Refund;
import com.swp391.sportcentermanagementsystem_v2.entity.Role;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRequestRepository;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Trả một lần cho cả yêu cầu nhiều buổi: đơn là tổng các buổi, trả xong cả chuỗi được đặt, hủy một buổi hoàn phần chia đều
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CoachSlotRequestPaymentTest {

    @Mock
    CoachSlotRepository slotRepository;
    @Mock
    CoachSlotRequestRepository requestRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    PaymentRepository paymentRepository;
    @Mock
    OrderRepository orderRepository;
    @Mock
    RefundService refundService;
    @Mock
    AuditService auditService;
    @Mock
    NotificationService notificationService;

    @InjectMocks
    CoachSlotService service;

    private CoachSlotRequest request;
    private CoachSlot first;
    private CoachSlot second;
    private CoachSlot third;

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

    private CoachSlot heldSlot(long id, int daysAhead) {
        OffsetDateTime start = OffsetDateTime.now().plusDays(daysAhead);
        CoachSlot s = new CoachSlot();
        s.setId(id);
        s.setCoach(user(10L, "COACH"));
        s.setMember(user(5L, "MEMBER"));
        s.setStatus("HELD");
        s.setHeldUntil(OffsetDateTime.now().plusHours(2));
        s.setStartAt(start);
        s.setEndAt(start.plusHours(1));
        s.setPrice(new BigDecimal("300000"));
        s.setRequest(request);
        when(slotRepository.findByIdForUpdate(id)).thenReturn(Optional.of(s));
        return s;
    }

    @BeforeEach
    void setUp() {
        request = new CoachSlotRequest();
        request.setId(77L);
        request.setSessions((short) 3);
        first = heldSlot(1L, 3);
        second = heldSlot(2L, 10);
        third = heldSlot(3L, 17);
        when(slotRepository.findByRequest_IdInOrderByStartAt(List.of(77L))).thenReturn(List.of(first, second, third));
        when(requestRepository.findById(77L)).thenReturn(Optional.of(request));
        when(userRepository.findByEmail("u1@example.com")).thenReturn(Optional.of(user(1L, "MANAGER")));
        when(slotRepository.saveAndFlush(any(CoachSlot.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void payingTheEarliestSessionBooksTheWholeRequest() {
        service.confirmPaid(1L);

        assertEquals("BOOKED", first.getStatus());
        assertEquals("BOOKED", second.getStatus());
        assertEquals("BOOKED", third.getStatus());
        assertNull(third.getHeldUntil());
        assertEquals("PAID", request.getStatus());
    }

    @Test
    void orderTotalIsTheSumOfEveryHeldSession() {
        OrderService orderService = new OrderService(orderRepository, null, slotRepository);
        when(orderRepository.findByCoachSlot_Id(1L)).thenReturn(Optional.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Order order = orderService.getOrCreateForSlot(first, user(5L, "MEMBER"), null, "ONLINE");

        assertEquals(new BigDecimal("900000"), order.getListPrice());
        assertEquals(new BigDecimal("900000"), order.getTotalAmount());
    }

    @Test
    void centerCancelingOneSessionRefundsAnEvenShareOfThePayment() {
        first.setStatus("BOOKED");
        first.setHeldUntil(null);
        Payment payment = new Payment();
        payment.setId(70L);
        payment.setAmount(new BigDecimal("900000"));
        when(paymentRepository.findFirstByOrder_CoachSlot_Request_IdAndStatusOrderByIdDesc(77L, "SUCCESS"))
                .thenReturn(Optional.of(payment));
        when(refundService.refundable(payment)).thenReturn(new BigDecimal("900000"));
        when(refundService.createPartialRefund(any(), any(), anyString())).thenAnswer(inv -> {
            Refund refund = new Refund();
            refund.setAmount(inv.getArgument(1));
            return refund;
        });

        service.managerCancel("u1@example.com", 1L, "coach unavailable");

        verify(refundService).createPartialRefund(eq(payment), eq(new BigDecimal("300000")), anyString());
    }

    @Test
    void rebookCancelsTheOldRowAndKeepsRequestPriceStatusAndOrder() {
        FacilityRoom room = new FacilityRoom();
        room.setId(9L);
        first.setStatus("BOOKED");
        first.setNeedsReschedule(true);
        Order order = new Order();
        order.setCoachSlot(first);
        when(orderRepository.findByCoachSlot_Id(1L)).thenReturn(Optional.of(order));

        CoachSlot moved = service.rebook(first, first.getCoach(), room, user(1L, "MANAGER"));

        assertEquals("CANCELED", first.getStatus());
        assertEquals("BOOKED", moved.getStatus());
        assertEquals(room, moved.getRoom());
        assertEquals(first.getStartAt(), moved.getStartAt());
        assertEquals(new BigDecimal("300000"), moved.getPrice());
        assertEquals(request, moved.getRequest());
        assertTrue(moved.isNeedsReschedule());
        assertEquals(moved, order.getCoachSlot());
    }
}
