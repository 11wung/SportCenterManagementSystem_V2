package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.PaymentResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassRegistration;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.Order;
import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import com.swp391.sportcentermanagementsystem_v2.entity.Role;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.MemberSubscriptionRepository;
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
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Thanh toán lớp và buổi 1-1 theo giá sau ưu đãi: số tiền lấy từ đơn, voucher được dùng khi trả tiền xong,
// tổng bằng 0 thì bỏ qua thanh toán
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentPricingTest {

    private static final String CASHIER_EMAIL = "cashier@example.com";
    private static final String MEMBER_EMAIL = "member@example.com";

    @Mock
    PaymentRepository paymentRepository;
    @Mock
    MemberSubscriptionRepository subscriptionRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    PlatformTransactionManager transactionManager;
    @Mock
    OrderService orderService;
    @Mock
    ClassRegistrationRepository classRegistrationRepository;
    @Mock
    ClassRegistrationService classRegistrationService;
    @Mock
    RefundService refundService;
    @Mock
    CoachSlotRepository coachSlotRepository;
    @Mock
    CoachSlotService coachSlotService;
    @Mock
    VoucherService voucherService;

    @InjectMocks
    PaymentService paymentService;

    private User cashier;
    private User member;
    private ClassRegistration registration;
    private CoachSlot slot;
    private Order classOrder;
    private Order slotOrder;

    private static User user(long id, String email, String role) {
        Role r = new Role();
        r.setCode(role);
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        u.setRole(r);
        u.setIsActive(true);
        return u;
    }

    @BeforeEach
    void setUp() {
        cashier = user(1L, CASHIER_EMAIL, "RECEPTIONIST");
        member = user(5L, MEMBER_EMAIL, "MEMBER");
        when(userRepository.findByEmail(CASHIER_EMAIL)).thenReturn(Optional.of(cashier));
        when(userRepository.findByEmail(MEMBER_EMAIL)).thenReturn(Optional.of(member));
        when(userRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(member));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepository.findFirstByOrder_Registration_IdAndPaymentMethodAndStatusInOrderByIdDesc(
                anyLong(), anyString(), anyList())).thenReturn(Optional.empty());
        when(paymentRepository.findFirstByOrder_CoachSlot_IdAndPaymentMethodAndStatusInOrderByIdDesc(
                anyLong(), anyString(), anyList())).thenReturn(Optional.empty());

        SportClass sportClass = new SportClass();
        sportClass.setTuition(new BigDecimal("1200000"));
        registration = new ClassRegistration();
        registration.setId(100L);
        registration.setUser(member);
        registration.setSportClass(sportClass);
        registration.setStatus("PENDING_PAYMENT");
        registration.setHoldExpiresAt(OffsetDateTime.now().plusMinutes(10));
        when(classRegistrationRepository.findById(100L)).thenReturn(Optional.of(registration));
        when(classRegistrationRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(registration));

        slot = new CoachSlot();
        slot.setId(200L);
        slot.setMember(member);
        slot.setStatus("HELD");
        slot.setHeldUntil(OffsetDateTime.now().plusMinutes(10));
        slot.setPrice(new BigDecimal("400000"));
        when(coachSlotRepository.findById(200L)).thenReturn(Optional.of(slot));
        when(coachSlotRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(slot));

        classOrder = new Order();
        classOrder.setId(50L);
        classOrder.setRegistration(registration);
        classOrder.setListPrice(new BigDecimal("1200000"));
        when(orderService.getOrCreateForRegistration(any(), any(), any(), anyString())).thenReturn(classOrder);
        when(orderService.applyPricing(eq(classOrder), any(), any())).thenReturn(classOrder);

        slotOrder = new Order();
        slotOrder.setId(51L);
        slotOrder.setCoachSlot(slot);
        slotOrder.setListPrice(new BigDecimal("400000"));
        when(orderService.getOrCreateForSlot(any(), any(), any(), anyString())).thenReturn(slotOrder);
        when(orderService.applyPricing(eq(slotOrder), any(), any())).thenReturn(slotOrder);
    }

    // ---- tiền mặt ----

    @Test
    void cashPaymentForClassChargesTheDiscountedOrderTotalAndUsesTheVoucher() {
        classOrder.setTotalAmount(new BigDecimal("1080000"));
        classOrder.setVoucherId(3L);

        PaymentResponse response = paymentService.payCashForRegistration(100L, CASHIER_EMAIL, 3L);

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).saveAndFlush(saved.capture());
        assertEquals(new BigDecimal("1080000"), saved.getValue().getAmount());
        assertEquals("CASH", saved.getValue().getPaymentMethod());
        assertEquals("PAID", classOrder.getStatus());
        verify(voucherService).markUsed(3L);
        verify(orderService).applyPricing(classOrder, member, 3L);
        verify(classRegistrationService).confirmPaid(100L);
        assertEquals(new BigDecimal("1080000"), response.getAmount());
    }

    @Test
    void cashPaymentForClassWithZeroTotalCreatesNoPayment() {
        classOrder.setTotalAmount(BigDecimal.ZERO);
        classOrder.setVoucherId(3L);

        PaymentResponse response = paymentService.payCashForRegistration(100L, CASHIER_EMAIL, 3L);

        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        verify(classRegistrationService).confirmPaid(100L);
        verify(voucherService).markUsed(3L);
        assertEquals("PAID", classOrder.getStatus());
        assertEquals("PAID", response.getPaymentStatus());
        assertEquals(0, response.getAmount().signum());
        assertEquals(100L, response.getRegistrationId());
    }

    @Test
    void cashPaymentForSlotWithZeroTotalCreatesNoPayment() {
        slotOrder.setTotalAmount(BigDecimal.ZERO);
        slotOrder.setVoucherId(4L);

        PaymentResponse response = paymentService.payCashForSlot(200L, CASHIER_EMAIL, 4L);

        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        verify(coachSlotService).confirmPaid(200L);
        verify(voucherService).markUsed(4L);
        assertEquals("PAID", slotOrder.getStatus());
        assertEquals(200L, response.getCoachSlotId());
    }

    // ---- QR online ----

    @Test
    void onlineQrForClassUsesTheDiscountedOrderTotal() {
        classOrder.setTotalAmount(new BigDecimal("1140000"));

        PaymentService.OnlineReservation reservation =
                paymentService.reserveOnlinePaymentForRegistration(100L, MEMBER_EMAIL, null);

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).saveAndFlush(saved.capture());
        assertEquals(new BigDecimal("1140000"), saved.getValue().getAmount());
        assertEquals(new BigDecimal("1140000"), reservation.amount());
        assertEquals("CREATING", reservation.status());
        assertTrue(reservation.newReservation());
        verify(voucherService, never()).markUsed(any());
    }

    @Test
    void onlineClassPaymentWithZeroTotalSkipsPayOS() {
        classOrder.setTotalAmount(BigDecimal.ZERO);
        classOrder.setVoucherId(3L);

        PaymentService.OnlineReservation reservation =
                paymentService.reserveOnlinePaymentForRegistration(100L, MEMBER_EMAIL, 3L);

        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        verify(classRegistrationService).confirmPaid(100L);
        verify(voucherService).markUsed(3L);
        assertNull(reservation.paymentId());
        assertEquals("PAID", reservation.status());
        assertEquals(0, reservation.amount().signum());
        assertEquals("PAID", classOrder.getStatus());
    }

    @Test
    void onlineSlotPaymentWithZeroTotalSkipsPayOS() {
        slotOrder.setTotalAmount(BigDecimal.ZERO);
        slotOrder.setVoucherId(4L);

        PaymentService.OnlineReservation reservation =
                paymentService.reserveOnlinePaymentForSlot(200L, MEMBER_EMAIL, 4L);

        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        verify(coachSlotService).confirmPaid(200L);
        verify(voucherService).markUsed(4L);
        assertNull(reservation.paymentId());
        assertEquals(200L, reservation.coachSlotId());
    }
}
