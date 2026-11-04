package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.PricingPreviewResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassRegistration;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
import com.swp391.sportcentermanagementsystem_v2.service.PricingService.Quote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    ClassRegistrationRepository registrationRepository;
    @Mock
    CoachSlotRepository slotRepository;

    @InjectMocks
    PricingService pricingService;

    private void tierGives(int classPct, int oneOnOnePct) {
        when(benefitService.intBenefit(eq(5L), eq("tuition_discount_pct"), any())).thenReturn(classPct);
        when(benefitService.intBenefit(eq(5L), eq("one_on_one_discount_pct"), any())).thenReturn(oneOnOnePct);
    }

    @Test
    void tierDiscountIsTheOnlyDiscount() {
        tierGives(5, 0);

        Quote q = pricingService.quote(5L, "CLASS", TUITION, TODAY);

        assertEquals("TIER", q.discountKind());
        assertEquals(new BigDecimal("60000"), q.discountAmount());
        assertEquals(new BigDecimal("1140000"), q.total());
    }

    @Test
    void noTierMeansNoDiscount() {
        tierGives(0, 0);

        Quote q = pricingService.quote(5L, "CLASS", TUITION, TODAY);

        assertEquals("NONE", q.discountKind());
        assertEquals(TUITION, q.total());
    }

    @Test
    void percentDiscountRoundsHalfUpToWholeDong() {
        tierGives(5, 0);

        // 1.234.570 x 5% = 61.728,5 -> 61.729
        Quote half = pricingService.quote(5L, "CLASS", new BigDecimal("1234570"), TODAY);
        // 1.234.567 x 5% = 61.728,35 -> 61.728
        Quote down = pricingService.quote(5L, "CLASS", new BigDecimal("1234567"), TODAY);

        assertEquals(new BigDecimal("61729"), half.discountAmount());
        assertEquals(new BigDecimal("61728"), down.discountAmount());
    }

    @Test
    void oneOnOneUsesItsOwnTierPercent() {
        tierGives(5, 10);

        Quote q = pricingService.quote(5L, "ONE_ON_ONE", new BigDecimal("400000"), TODAY);

        assertEquals(new BigDecimal("40000"), q.discountAmount());
    }

    @Test
    void packageGetsNoTierDiscount() {
        tierGives(15, 20);

        Quote q = pricingService.quote(5L, "PACKAGE", new BigDecimal("1000000"), TODAY);

        assertEquals("NONE", q.discountKind());
        assertEquals(new BigDecimal("1000000"), q.total());
    }

    @Test
    void previewPricesTheMembersRegistration() {
        tierGives(5, 0);
        User member = new User();
        member.setId(5L);
        SportClass sportClass = new SportClass();
        sportClass.setTuition(TUITION);
        ClassRegistration registration = new ClassRegistration();
        registration.setUser(member);
        registration.setSportClass(sportClass);
        when(registrationRepository.findById(12L)).thenReturn(Optional.of(registration));

        PricingPreviewResponse preview = pricingService.preview(5L, "CLASS", 12L);

        assertEquals(TUITION, preview.getListPrice());
        assertEquals("TIER", preview.getDiscountKind());
        assertEquals(new BigDecimal("60000"), preview.getDiscountAmount());
        assertEquals(new BigDecimal("1140000"), preview.getTotal());
    }
}
