package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.VoucherListResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.VoucherResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassRegistration;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.OrderRepository;
import com.swp391.sportcentermanagementsystem_v2.service.PricingService.Quote;
import com.swp391.sportcentermanagementsystem_v2.service.VoucherService.Voucher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PricingServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final BigDecimal TUITION = new BigDecimal("1200000");

    @Mock
    BenefitService benefitService;
    @Mock
    VoucherService voucherService;
    @Mock
    ClassRegistrationRepository registrationRepository;
    @Mock
    CoachSlotRepository slotRepository;
    @Mock
    OrderRepository orderRepository;

    @InjectMocks
    PricingService pricingService;

    private Voucher voucher(long id, String kind, String value, String appliesTo) {
        return new Voucher(id, 5L, "COMPLETION", kind, new BigDecimal(value), appliesTo,
                OffsetDateTime.now().plusDays(10 + id), null);
    }

    private void tierGives(int classPct, int oneOnOnePct) {
        when(benefitService.intBenefit(eq(5L), eq("tuition_discount_pct"), any())).thenReturn(classPct);
        when(benefitService.intBenefit(eq(5L), eq("one_on_one_discount_pct"), any())).thenReturn(oneOnOnePct);
    }

    @Test
    void tierDiscountWinsWhenLargerThanVoucher() {
        tierGives(5, 0);

        Quote q = pricingService.quote(5L, "CLASS", TUITION, voucher(1, "PERCENT", "3", "CLASS"), TODAY);

        assertEquals("TIER", q.discountKind());
        assertEquals(new BigDecimal("60000"), q.discountAmount());
        assertEquals(null, q.voucherId());
        assertEquals(new BigDecimal("1140000"), q.total());
    }

    @Test
    void voucherWinsWhenLargerThanTier() {
        tierGives(5, 0);

        Quote q = pricingService.quote(5L, "CLASS", TUITION, voucher(1, "PERCENT", "10", "CLASS"), TODAY);

        assertEquals("VOUCHER", q.discountKind());
        assertEquals(new BigDecimal("120000"), q.discountAmount());
        assertEquals(1L, q.voucherId());
        assertEquals(new BigDecimal("1080000"), q.total());
    }

    @Test
    void tierIsUsedWhenEqualSoVoucherIsKept() {
        tierGives(10, 0);

        Quote q = pricingService.quote(5L, "CLASS", TUITION, voucher(1, "PERCENT", "10", "CLASS"), TODAY);

        assertEquals("TIER", q.discountKind());
        assertEquals(null, q.voucherId());
    }

    @Test
    void percentDiscountRoundsHalfUpToWholeDong() {
        tierGives(5, 0);

        // 1.234.570 x 5% = 61.728,5 -> 61.729
        Quote half = pricingService.quote(5L, "CLASS", new BigDecimal("1234570"), null, TODAY);
        // 1.234.567 x 5% = 61.728,35 -> 61.728
        Quote down = pricingService.quote(5L, "CLASS", new BigDecimal("1234567"), null, TODAY);

        assertEquals(new BigDecimal("61729"), half.discountAmount());
        assertEquals(new BigDecimal("61728"), down.discountAmount());
    }

    @Test
    void freeOneOnOneVoucherMakesTotalZero() {
        tierGives(0, 10);

        Quote q = pricingService.quote(5L, "ONE_ON_ONE", new BigDecimal("400000"),
                voucher(1, "FREE_ONE_ON_ONE", "0", "ONE_ON_ONE"), TODAY);

        assertEquals("VOUCHER", q.discountKind());
        assertEquals(0, q.total().signum());
    }

    @Test
    void amountVoucherIsCappedAtListPrice() {
        tierGives(0, 0);

        Quote q = pricingService.quote(5L, "CLASS", new BigDecimal("300000"),
                voucher(1, "AMOUNT", "500000", "CLASS"), TODAY);

        assertEquals(new BigDecimal("300000"), q.discountAmount());
        assertEquals(0, q.total().signum());
    }

    @Test
    void packageGetsNoTierDiscount() {
        tierGives(15, 20);

        Quote q = pricingService.quote(5L, "PACKAGE", new BigDecimal("1000000"), null, TODAY);

        assertEquals("NONE", q.discountKind());
        assertEquals(new BigDecimal("1000000"), q.total());
    }

    @Test
    void voucherListIsSortedByMoneySavedAndRecommendsOnlyIfBetterThanTier() {
        tierGives(5, 0);
        User member = new User();
        member.setId(5L);
        SportClass sportClass = new SportClass();
        sportClass.setTuition(TUITION);
        ClassRegistration registration = new ClassRegistration();
        registration.setUser(member);
        registration.setSportClass(sportClass);
        when(registrationRepository.findById(12L)).thenReturn(Optional.of(registration));
        when(orderRepository.findByRegistration_Id(12L)).thenReturn(Optional.empty());

        Voucher small = voucher(1, "PERCENT", "8", "CLASS");
        Voucher best = voucher(2, "AMOUNT", "200000", "CLASS");
        Voucher held = voucher(3, "PERCENT", "50", "CLASS");
        when(voucherService.findUsable(5L, "CLASS")).thenReturn(List.of(small, best, held));
        when(voucherService.isHeldByOtherOrder(eq(3L), eq(-1L))).thenReturn(true);

        VoucherListResponse list = pricingService.vouchers(5L, "CLASS", 12L);

        List<Long> ids = list.getVouchers().stream().map(VoucherResponse::getId).toList();
        assertEquals(List.of(2L, 1L, 3L), ids);
        assertEquals(new BigDecimal("60000"), list.getTierDiscountAmount());
        assertTrue(list.getVouchers().get(0).isRecommended());
        assertFalse(list.getVouchers().get(1).isRecommended());
        assertFalse(list.getVouchers().get(2).isUsable());
    }

    @Test
    void voucherListWithoutTargetIsSortedByPercentThenAmountThenFree() {
        List<Voucher> unsorted = List.of(
                voucher(1, "FREE_ONE_ON_ONE", "0", "ONE_ON_ONE"),
                voucher(2, "AMOUNT", "100000", "CLASS"),
                voucher(3, "PERCENT", "10", "CLASS"),
                voucher(4, "PERCENT", "20", "CLASS"));
        when(voucherService.findUsable(5L, null)).thenReturn(unsorted);
        when(voucherService.isHeldByOtherOrder(any(), eq(-1L))).thenReturn(false);

        VoucherListResponse list = pricingService.vouchers(5L, null, null);

        assertEquals(List.of(4L, 3L, 2L, 1L), list.getVouchers().stream().map(VoucherResponse::getId).toList());
    }
}
