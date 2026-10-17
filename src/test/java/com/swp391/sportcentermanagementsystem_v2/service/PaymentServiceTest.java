package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.PaymentResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.MemberSubscription;
import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import com.swp391.sportcentermanagementsystem_v2.entity.Role;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.MemberSubscriptionRepository;
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
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String RECEPTIONIST_EMAIL = "r@test.com";

    @Mock
    PaymentRepository paymentRepository;
    @Mock
    MemberSubscriptionRepository subscriptionRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    PlatformTransactionManager transactionManager;

    @InjectMocks
    PaymentService paymentService;

    private User receptionist;
    private User member;

    @BeforeEach
    void setUp() {
        receptionist = user(1L, "RECEPTIONIST");
        member = user(5L, "MEMBER");

        when(userRepository.findByEmail(RECEPTIONIST_EMAIL)).thenReturn(Optional.of(receptionist));
        when(userRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(member));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepository.existsBySubscription_IdAndStatusIn(anyLong(), anyList())).thenReturn(false);
    }

    // ---------- Cash ----------

    @Test
    void cashPaymentActivatesNewSubscriptionFromToday() {
        MemberSubscription sub = pendingSubscription(10L, "NEW", 3);
        stubSubscription(sub);
        stubPaidSubscriptions(List.of(), Optional.empty());

        PaymentResponse response = paymentService.payCash(10L, RECEPTIONIST_EMAIL);

        LocalDate today = LocalDate.now(VN);
        assertEquals("SUCCESS", response.getPaymentStatus());
        assertEquals("ACTIVE", sub.getStatus());
        assertEquals(today, sub.getStartDate());
        assertEquals(today.plusMonths(3), sub.getEndDate());
    }

    @Test
    void cashRenewalStartsWhenCurrentSubscriptionEnds() {
        LocalDate today = LocalDate.now(VN);

        MemberSubscription current = new MemberSubscription();
        current.setId(9L);
        current.setUser(member);
        current.setStatus("ACTIVE");
        current.setStartDate(today.minusDays(20));
        current.setEndDate(today.plusDays(10));

        MemberSubscription renewal = pendingSubscription(10L, "RENEWAL", 1);
        stubSubscription(renewal);
        stubPaidSubscriptions(List.of(current), Optional.of(current));

        paymentService.payCash(10L, RECEPTIONIST_EMAIL);

        assertEquals("SCHEDULED", renewal.getStatus());
        assertEquals(today.plusDays(10), renewal.getStartDate());
        assertEquals(today.plusDays(10).plusMonths(1), renewal.getEndDate());
    }

    @Test
    void cashPaymentRejectedWhenSubscriptionAlreadyPaid() {
        MemberSubscription sub = pendingSubscription(10L, "NEW", 3);
        sub.setStatus("ACTIVE");
        stubSubscription(sub);

        assertThrows(BusinessRuleException.class,
                () -> paymentService.payCash(10L, RECEPTIONIST_EMAIL));
        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
    }

    @Test
    void cashPaymentRejectedForOrderCreatedByTheMemberOnTheApp() {
        // Đơn Member tự tạo (createdBy rỗng) chỉ trả online, lễ tân không thu tiền mặt được.
        MemberSubscription sub = pendingSubscription(10L, "NEW", 3);
        sub.setCreatedBy(null);
        stubSubscription(sub);

        assertThrows(BusinessRuleException.class,
                () -> paymentService.payCash(10L, RECEPTIONIST_EMAIL));

        assertEquals("PENDING", sub.getStatus());
        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
    }

    // ---------- Online (PayOS) ----------

    @Test
    void verifiedPaymentWithoutPaidTimeStillGetsPaidAtAndNeedsReview() {
        // DB bắt buộc SUCCESS phải có paid_at.
        MemberSubscription sub = pendingSubscription(10L, "NEW", 3);
        Payment payment = onlinePayment(7L, sub, "PENDING");
        stubOnline(sub, payment);

        PaymentResponse response = paymentService.confirmVerifiedOnlinePayment(
                7L, 7L, new BigDecimal("500000"), null);

        assertEquals("SUCCESS", payment.getStatus());
        assertNotNull(payment.getPaidAt());
        assertTrue(response.getRequiresReview());
        assertEquals("PENDING", sub.getStatus());
    }

    @Test
    void repeatedWebhookDoesNotRecordOrActivateAgain() {
        MemberSubscription sub = pendingSubscription(10L, "NEW", 3);
        Payment payment = onlinePayment(7L, sub, "SUCCESS");
        payment.setPaidAt(OffsetDateTime.now(VN));
        stubOnline(sub, payment);

        paymentService.confirmVerifiedOnlinePayment(
                7L, 7L, new BigDecimal("500000"), OffsetDateTime.now(VN));

        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        assertEquals("PENDING", sub.getStatus());
    }

    // ---------- Helpers ----------

    private User user(Long id, String roleCode) {
        Role role = new Role();
        role.setCode(roleCode);

        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setIsActive(true);
        return user;
    }

    private MemberSubscription pendingSubscription(Long id, String purchaseType, int months) {
        MemberSubscription sub = new MemberSubscription();
        sub.setId(id);
        sub.setUser(member);
        sub.setCreatedBy(receptionist);   // đơn do lễ tân tạo tại quầy
        sub.setStatus("PENDING");
        sub.setPurchaseType(purchaseType);
        sub.setTotalAmount(new BigDecimal("500000"));
        sub.setDurationMonthsSnapshot((short) months);
        return sub;
    }

    private Payment onlinePayment(Long id, MemberSubscription sub, String status) {
        Payment payment = new Payment();
        payment.setId(id);
        payment.setSubscription(sub);
        payment.setPayer(member);
        payment.setPaymentMethod("QR_GATEWAY");
        payment.setGatewayOrderCode(id);
        payment.setAmount(new BigDecimal("500000"));
        payment.setStatus(status);
        payment.setExpiresAt(OffsetDateTime.now(VN).plusMinutes(10));
        return payment;
    }

    private void stubSubscription(MemberSubscription sub) {
        when(subscriptionRepository.findById(sub.getId())).thenReturn(Optional.of(sub));
        when(subscriptionRepository.findByIdForUpdate(sub.getId())).thenReturn(Optional.of(sub));
    }

    private void stubPaidSubscriptions(List<MemberSubscription> paid, Optional<MemberSubscription> latest) {
        when(subscriptionRepository.findByUserIdAndStatusIn(eq(5L), anyList())).thenReturn(paid);
        when(subscriptionRepository.findFirstByUserIdAndStatusInOrderByEndDateDesc(eq(5L), anyList()))
                .thenReturn(latest);
    }

    private void stubOnline(MemberSubscription sub, Payment payment) {
        stubSubscription(sub);
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(paymentRepository.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
    }
}
