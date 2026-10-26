package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.Order;
import com.swp391.sportcentermanagementsystem_v2.repository.OrderRepository;
import static org.mockito.Mockito.verify;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateSubscriptionRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.MemberSubscriptionResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.MemberSubscription;
import com.swp391.sportcentermanagementsystem_v2.entity.MembershipPackage;
import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import com.swp391.sportcentermanagementsystem_v2.entity.Role;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.MemberSubscriptionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.MembershipPackageRepository;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemberSubscriptionServiceTest {

    @Mock
    MemberSubscriptionRepository subscriptionRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    MembershipPackageRepository packageRepository;
    @Mock
    PaymentRepository paymentRepository;
    @Mock
    OrderRepository orderRepository;
    @Mock
    MembershipPackageService packageService;
    @Mock
    RefundService refundService;
    @Mock
    NotificationService notificationService;
    @Mock
    PricingService pricingService;
    @Mock
    VoucherService voucherService;

    @InjectMocks
    MemberSubscriptionService subscriptionService;

    private MemberSubscription order;

    @BeforeEach
    void setUp() {
        User member = new User();
        member.setId(5L);

        order = new MemberSubscription();
        order.setId(10L);
        order.setUser(member);
        order.setStatus("PENDING");

        when(userRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(member));
        when(subscriptionRepository.findById(10L)).thenReturn(Optional.of(order));
        when(paymentRepository.existsBySubscription_IdAndStatusIn(anyLong(), anyList())).thenReturn(false);
    }

    @Test
    void memberCancelsOwnPendingOrder() {
        subscriptionService.cancelPendingOrder(5L, 10L);

        assertEquals("CANCELED", order.getStatus());
    }

    @Test
    void memberCannotCancelSomeoneElsesOrder() {
        User other = new User();
        other.setId(6L);
        when(userRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(other));

        assertThrows(BusinessRuleException.class,
                () -> subscriptionService.cancelPendingOrder(6L, 10L));

        assertEquals("PENDING", order.getStatus());
    }

    @Test
    void orderWithPaymentInProgressCannotBeCanceled() {
        when(paymentRepository.existsBySubscription_IdAndStatusIn(anyLong(), anyList())).thenReturn(true);

        assertThrows(BusinessRuleException.class,
                () -> subscriptionService.cancelPendingOrder(5L, 10L));

        assertEquals("PENDING", order.getStatus());
    }

    @Test
    void onlyPendingOrdersCanBeCanceled() {
        order.setStatus("ACTIVE");

        assertThrows(BusinessRuleException.class,
                () -> subscriptionService.cancelPendingOrder(5L, 10L));

        assertEquals("ACTIVE", order.getStatus());
    }

    @Test
    void notStartedScheduledPackageIsCanceledWithFullRefund() {
        order.setStatus("SCHEDULED");
        order.setStartDate(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(5));
        order.setPackageNameSnapshot("Bứt Tốc");
        Payment paid = new Payment();
        paid.setAmount(new BigDecimal("2070000"));
        when(subscriptionRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(order));
        when(paymentRepository.findFirstByOrder_Subscription_IdAndStatusOrderByIdDesc(10L, "SUCCESS"))
                .thenReturn(Optional.of(paid));

        subscriptionService.cancelPendingOrder(5L, 10L);

        assertEquals("CANCELED", order.getStatus());
        verify(refundService).createRefund(paid, new BigDecimal("2070000"), "Package canceled before it started");
    }

    @Test
    void scheduledPackageThatAlreadyStartedCannotBeCanceled() {
        order.setStatus("SCHEDULED");
        order.setStartDate(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")));
        when(subscriptionRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(order));

        assertThrows(BusinessRuleException.class,
                () -> subscriptionService.cancelPendingOrder(5L, 10L));

        assertEquals("SCHEDULED", order.getStatus());
    }

    // Dựng Member và tier giá 100.000đ / tháng để thử tạo đơn theo số tháng.
    private MemberSubscriptionResponse createOrder(int months) {
        return createOrder(months, null);
    }

    private MemberSubscriptionResponse createOrder(int months, Long voucherId) {
        Role role = new Role();
        role.setCode("MEMBER");
        User buyer = new User();
        buyer.setId(7L);
        buyer.setRole(role);

        MembershipPackage pack = new MembershipPackage();
        pack.setId(1L);
        pack.setName("Basic");
        pack.setMonthlyPrice(new BigDecimal("100000"));
        pack.setTierLevel((short) 1);

        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(buyer));
        when(packageRepository.findByIdAndIsActiveTrue(1L)).thenReturn(Optional.of(pack));
        when(subscriptionRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        CreateSubscriptionRequest request = new CreateSubscriptionRequest();
        request.setPackageId(1L);
        request.setMonths(months);
        request.setVoucherId(voucherId);
        when(packageService.calculateTotal(pack, months))
                .thenReturn(new BigDecimal("100000").multiply(BigDecimal.valueOf(months)));

        return subscriptionService.createSubscriptionOrder(7L, request, null, "NEW");
    }

    @Test
    void buyingThreeMonthsUsesPackageTotalAndSavesOrder() {
        MemberSubscriptionResponse result = createOrder(3);

        assertEquals(new BigDecimal("300000"), result.getTotalAmount());
        assertEquals((short) 3, result.getMonths());
        verify(orderRepository).save(any(Order.class));
    }

    private VoucherService.Voucher packageVoucher() {
        return new VoucherService.Voucher(3L, 7L, "WINBACK", "PERCENT", BigDecimal.TEN, "PACKAGE",
                java.time.OffsetDateTime.now().plusDays(10), null);
    }

    @Test
    void packageVoucherReducesTheOrderTotalAndIsLinkedToTheOrder() {
        VoucherService.Voucher voucher = packageVoucher();
        when(voucherService.findById(3L)).thenReturn(Optional.of(voucher));
        when(pricingService.unusableReason(voucher, 7L, "PACKAGE")).thenReturn(Optional.empty());
        when(voucherService.isHeldByOtherOrder(3L, -1L)).thenReturn(false);
        when(pricingService.quote(eq(7L), eq("PACKAGE"), eq(new BigDecimal("300000")), eq(voucher), any()))
                .thenReturn(new PricingService.Quote(new BigDecimal("300000"), BigDecimal.ZERO,
                        new BigDecimal("30000"), "VOUCHER", new BigDecimal("30000"), 3L));

        MemberSubscriptionResponse result = createOrder(3, 3L);

        assertEquals(new BigDecimal("270000"), result.getTotalAmount());
        org.mockito.ArgumentCaptor<Order> saved = org.mockito.ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        assertEquals(new BigDecimal("300000"), saved.getValue().getListPrice());
        assertEquals("VOUCHER", saved.getValue().getDiscountKind());
        assertEquals(3L, saved.getValue().getVoucherId());
        assertEquals(new BigDecimal("270000"), saved.getValue().getTotalAmount());
    }

    @Test
    void unusablePackageVoucherIsRejected() {
        VoucherService.Voucher voucher = packageVoucher();
        when(voucherService.findById(3L)).thenReturn(Optional.of(voucher));
        when(pricingService.unusableReason(voucher, 7L, "PACKAGE")).thenReturn(Optional.of("Voucher has expired!"));

        BusinessRuleException ex = assertThrows(BusinessRuleException.class, () -> createOrder(3, 3L));

        assertEquals("Voucher has expired!", ex.getMessage());
        verify(orderRepository, org.mockito.Mockito.never()).save(any(Order.class));
    }

    @Test
    void packageVoucherHeldByAnotherPaymentIsRejected() {
        VoucherService.Voucher voucher = packageVoucher();
        when(voucherService.findById(3L)).thenReturn(Optional.of(voucher));
        when(pricingService.unusableReason(voucher, 7L, "PACKAGE")).thenReturn(Optional.empty());
        when(voucherService.isHeldByOtherOrder(3L, -1L)).thenReturn(true);

        assertThrows(BusinessRuleException.class, () -> createOrder(3, 3L));
        verify(orderRepository, org.mockito.Mockito.never()).save(any(Order.class));
    }

    @Test
    void voucherThatCoversTheWholePriceIsRejected() {
        VoucherService.Voucher voucher = new VoucherService.Voucher(3L, 7L, "CAMPAIGN", "AMOUNT",
                new BigDecimal("999999"), "PACKAGE", java.time.OffsetDateTime.now().plusDays(10), null);
        when(voucherService.findById(3L)).thenReturn(Optional.of(voucher));
        when(pricingService.unusableReason(voucher, 7L, "PACKAGE")).thenReturn(Optional.empty());
        when(pricingService.quote(eq(7L), eq("PACKAGE"), any(), eq(voucher), any()))
                .thenReturn(new PricingService.Quote(new BigDecimal("300000"), BigDecimal.ZERO,
                        new BigDecimal("300000"), "VOUCHER", new BigDecimal("300000"), 3L));

        assertThrows(BusinessRuleException.class, () -> createOrder(3, 3L));
        verify(orderRepository, org.mockito.Mockito.never()).save(any(Order.class));
    }

    @Test
    void monthsDefaultsToOne() {
        assertEquals(1, new CreateSubscriptionRequest().getMonths());
    }
}
