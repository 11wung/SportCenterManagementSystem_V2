package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.InvoiceResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.PaymentResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.MemberSubscription;
import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import com.swp391.sportcentermanagementsystem_v2.entity.PurchaseOrder;
import com.swp391.sportcentermanagementsystem_v2.entity.Role;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

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
    @Mock
    OrderService orderService;
    @Mock
    ClassRegistrationRepository classRegistrationRepository;
    @Mock
    ClassRegistrationService classRegistrationService;
    @Mock
    RefundService refundService;

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
        when(orderService.getOrCreateForSubscription(any(MemberSubscription.class), any(), any(), anyString()))
                .thenAnswer(inv -> orderOf(inv.getArgument(0)));
    }

    // Order của gói trong test: một order cho mỗi lần gọi, đủ để Payment có order_id
    private PurchaseOrder orderOf(MemberSubscription sub) {
        PurchaseOrder order = new PurchaseOrder();
        order.setSubscription(sub);
        order.setMember(member);
        order.setChannel("ONLINE");
        order.setListPrice(sub.getTotalAmount() == null ? BigDecimal.ZERO : sub.getTotalAmount());
        order.setTotalAmount(order.getListPrice());
        return order;
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
    void cashPaymentAcceptedForOrderCreatedByTheMemberOnTheApp() {
        // Đơn Member tự tạo (createdBy rỗng): lễ tân thu tiền mặt tại quầy và được ghi nhận là người tiếp nhận.
        MemberSubscription sub = pendingSubscription(10L, "NEW", 3);
        sub.setCreatedBy(null);
        stubSubscription(sub);
        stubPaidSubscriptions(List.of(), Optional.empty());

        paymentService.payCash(10L, RECEPTIONIST_EMAIL);

        assertEquals("ACTIVE", sub.getStatus());
        assertEquals(receptionist, sub.getCreatedBy());
    }

    @Test
    void cashPaymentRejectedWhileOnlinePaymentIsStillOpen() {
        MemberSubscription sub = pendingSubscription(10L, "NEW", 3);
        stubSubscription(sub);
        when(paymentRepository.existsBySubscription_IdAndStatusIn(anyLong(), anyList())).thenReturn(true);

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

    // ---------- Invoice ----------

    private Payment paidPayment(Long id, String method, User receivedBy) {
        member.setFullName("Lan Nguyen");
        member.setEmail("lan@test.com");
        member.setPhone("0933000111");

        MemberSubscription sub = pendingSubscription(10L, "UPGRADE", 1);
        sub.setStatus("ACTIVE");
        sub.setPackageNameSnapshot("Starter");
        sub.setUpgradeCreditAmount(new BigDecimal("169354"));
        sub.setStartDate(LocalDate.of(2026, 10, 26));
        sub.setEndDate(LocalDate.of(2026, 11, 26));

        Payment payment = new Payment();
        payment.setId(id);
        payment.setOrder(orderOf(sub));
        payment.setPayer(member);
        payment.setReceivedBy(receivedBy);
        payment.setInvoiceNumber("INV-TEST");
        payment.setTransactionCode("CODE-TEST");
        payment.setAmount(new BigDecimal("230646"));
        payment.setPaymentMethod(method);
        payment.setStatus("SUCCESS");
        payment.setPaidAt(OffsetDateTime.now(VN));

        when(paymentRepository.findWithDetailsById(id)).thenReturn(Optional.of(payment));
        return payment;
    }

    @Test
    void invoiceOfCashPaymentShowsPackageMoneyAndWhoReceivedIt() {
        receptionist.setFullName("Nguyen Receptionist");
        paidPayment(7L, "CASH", receptionist);

        InvoiceResponse invoice = paymentService.getInvoice(7L);

        assertEquals("INV-TEST", invoice.getInvoiceNumber());
        assertEquals("Lan Nguyen", invoice.getMemberName());
        assertEquals("Starter", invoice.getPackageName());
        assertEquals("CASH", invoice.getPaymentMethod());
        assertEquals("Nguyen Receptionist", invoice.getReceivedByName());
        // Giá gói = số tiền trả + tiền được trừ khi nâng cấp.
        assertEquals(new BigDecimal("230646"), invoice.getAmount());
        assertEquals(new BigDecimal("169354"), invoice.getUpgradeCreditAmount());
        assertEquals(new BigDecimal("400000"), invoice.getPackagePrice());
    }

    @Test
    void invoiceOfOnlinePaymentHasNoReceptionist() {
        paidPayment(7L, "QR_GATEWAY", null);

        InvoiceResponse invoice = paymentService.getInvoice(7L);

        assertNull(invoice.getReceivedByName());
    }

    @Test
    void paymentThatIsNotPaidYetHasNoInvoice() {
        Payment payment = paidPayment(7L, "QR_GATEWAY", null);
        payment.setStatus("PENDING");

        assertThrows(BusinessRuleException.class, () -> paymentService.getInvoice(7L));
    }

    @Test
    void memberSeesTheirOwnInvoice() {
        paidPayment(7L, "CASH", receptionist);

        InvoiceResponse invoice = paymentService.getMyInvoice(7L, "lan@test.com");

        assertEquals("INV-TEST", invoice.getInvoiceNumber());
    }

    @Test
    void memberCannotSeeAnotherMembersInvoice() {
        // Trả 404 như "không tồn tại" để không lộ hóa đơn nào đang có.
        paidPayment(7L, "CASH", receptionist);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> paymentService.getMyInvoice(7L, "someone-else@test.com"));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void memberListOnlyAsksForTheirOwnPaidPayments() {
        Payment payment = paidPayment(7L, "CASH", receptionist);
        when(userRepository.findByEmail("lan@test.com")).thenReturn(Optional.of(member));
        when(paymentRepository.findByPayer_IdAndStatusOrderByPaidAtDesc(5L, "SUCCESS"))
                .thenReturn(List.of(payment));

        List<PaymentResponse> result = paymentService.getMyPayments("lan@test.com");

        assertEquals(1, result.size());
        assertEquals("INV-TEST", result.get(0).getInvoiceNumber());
        verify(paymentRepository).findByPayer_IdAndStatusOrderByPaidAtDesc(5L, "SUCCESS");
    }

    @Test
    void receptionistCannotListPaymentsOfSomeoneWhoIsNotAMember() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(receptionist));

        assertThrows(ResponseStatusException.class, () -> paymentService.getMemberPayments(1L));
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
        payment.setOrder(orderOf(sub));
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
