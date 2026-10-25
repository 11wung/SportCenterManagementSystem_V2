package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.ClassRegistration;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.Order;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.OrderRepository;
import com.swp391.sportcentermanagementsystem_v2.service.PricingService.Quote;
import com.swp391.sportcentermanagementsystem_v2.service.VoucherService.Voucher;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// OrderService.applyPricing: ghi ưu đãi vào đơn và chặn voucher không hợp lệ
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceTest {

    @Mock
    OrderRepository orderRepository;
    @Mock
    PricingService pricingService;
    @Mock
    VoucherService voucherService;

    @InjectMocks
    OrderService orderService;

    private User member;
    private Order classOrder;

    @BeforeEach
    void setUp() {
        member = new User();
        member.setId(5L);

        classOrder = new Order();
        classOrder.setId(50L);
        classOrder.setRegistration(new ClassRegistration());
        classOrder.setListPrice(new BigDecimal("1200000"));
        classOrder.setTotalAmount(new BigDecimal("1200000"));
    }

    private Voucher voucher() {
        return new Voucher(3L, 5L, "COMPLETION", "PERCENT", BigDecimal.TEN, "CLASS",
                OffsetDateTime.now().plusDays(5), null);
    }

    @Test
    void tierDiscountIsWrittenIntoTheOrderWithoutAVoucher() {
        when(pricingService.quote(eq(5L), eq("CLASS"), eq(classOrder.getListPrice()), eq(null), any()))
                .thenReturn(new Quote(classOrder.getListPrice(), new BigDecimal("60000"), BigDecimal.ZERO,
                        "TIER", new BigDecimal("60000"), null));

        Order priced = orderService.applyPricing(classOrder, member, null);

        assertEquals("TIER", priced.getDiscountKind());
        assertEquals(new BigDecimal("60000"), priced.getDiscountAmount());
        assertNull(priced.getVoucherId());
        assertEquals(new BigDecimal("1140000"), priced.getTotalAmount());
    }

    @Test
    void winningVoucherIsLinkedToTheOrder() {
        Voucher voucher = voucher();
        when(voucherService.findById(3L)).thenReturn(Optional.of(voucher));
        when(pricingService.unusableReason(voucher, 5L, "CLASS")).thenReturn(Optional.empty());
        when(voucherService.isHeldByOtherOrder(3L, 50L)).thenReturn(false);
        when(pricingService.quote(eq(5L), eq("CLASS"), any(), eq(voucher), any()))
                .thenReturn(new Quote(classOrder.getListPrice(), new BigDecimal("60000"), new BigDecimal("120000"),
                        "VOUCHER", new BigDecimal("120000"), 3L));

        Order priced = orderService.applyPricing(classOrder, member, 3L);

        assertEquals("VOUCHER", priced.getDiscountKind());
        assertEquals(3L, priced.getVoucherId());
        assertEquals(new BigDecimal("1080000"), priced.getTotalAmount());
    }

    @Test
    void oneOnOneOrderIsPricedAsOneOnOne() {
        Order slotOrder = new Order();
        slotOrder.setId(51L);
        slotOrder.setCoachSlot(new CoachSlot());
        slotOrder.setListPrice(new BigDecimal("400000"));
        when(pricingService.quote(eq(5L), eq("ONE_ON_ONE"), any(), eq(null), any()))
                .thenReturn(new Quote(slotOrder.getListPrice(), BigDecimal.ZERO, BigDecimal.ZERO,
                        "NONE", BigDecimal.ZERO, null));

        orderService.applyPricing(slotOrder, member, null);

        verify(pricingService).quote(eq(5L), eq("ONE_ON_ONE"), any(), eq(null), any());
    }

    @Test
    void unknownVoucherIsRejected() {
        when(voucherService.findById(9L)).thenReturn(Optional.empty());

        assertThrows(BusinessRuleException.class, () -> orderService.applyPricing(classOrder, member, 9L));
    }

    @Test
    void unusableVoucherIsRejectedWithItsReason() {
        Voucher voucher = voucher();
        when(voucherService.findById(3L)).thenReturn(Optional.of(voucher));
        when(pricingService.unusableReason(voucher, 5L, "CLASS"))
                .thenReturn(Optional.of("Voucher has expired!"));

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> orderService.applyPricing(classOrder, member, 3L));

        assertEquals("Voucher has expired!", ex.getMessage());
    }

    @Test
    void voucherHeldByAnotherPaymentIsRejected() {
        Voucher voucher = voucher();
        when(voucherService.findById(3L)).thenReturn(Optional.of(voucher));
        when(pricingService.unusableReason(voucher, 5L, "CLASS")).thenReturn(Optional.empty());
        when(voucherService.isHeldByOtherOrder(3L, 50L)).thenReturn(true);

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> orderService.applyPricing(classOrder, member, 3L));

        assertTrue(ex.getMessage().contains("another payment"));
    }
}
