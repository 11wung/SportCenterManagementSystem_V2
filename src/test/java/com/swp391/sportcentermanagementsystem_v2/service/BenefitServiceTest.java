package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.MemberSubscription;
import com.swp391.sportcentermanagementsystem_v2.entity.SubscriptionFreeze;
import com.swp391.sportcentermanagementsystem_v2.repository.MemberSubscriptionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SubscriptionFreezeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BenefitServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    @Mock
    MemberSubscriptionRepository subscriptionRepository;
    @Mock
    SubscriptionFreezeRepository freezeRepository;

    @InjectMocks
    BenefitService benefitService;

    private MemberSubscription subscription;

    @BeforeEach
    void setUp() {
        subscription = new MemberSubscription();
        subscription.setId(10L);
        subscription.setBenefitsSnapshot(Map.of("early_register_days", 5, "waitlist_priority", 2));
    }

    @Test
    void memberWithoutActiveSubscriptionHasNoBenefits() {
        when(subscriptionRepository.findValidActiveSubscription(5L, TODAY)).thenReturn(Optional.empty());

        assertEquals(0, benefitService.intBenefit(5L, "early_register_days", TODAY));
    }

    @Test
    void activeSubscriptionGivesSnapshotBenefits() {
        when(subscriptionRepository.findValidActiveSubscription(5L, TODAY)).thenReturn(Optional.of(subscription));
        when(freezeRepository
                .findFirstBySubscription_IdAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        eq(10L), anyList(), any(), any()))
                .thenReturn(Optional.empty());

        assertEquals(5, benefitService.intBenefit(5L, "early_register_days", TODAY));
        assertEquals(0, benefitService.intBenefit(5L, "unknown_key", TODAY));
    }

    @Test
    void frozenSubscriptionGivesNoBenefits() {
        when(subscriptionRepository.findValidActiveSubscription(5L, TODAY)).thenReturn(Optional.of(subscription));
        when(freezeRepository
                .findFirstBySubscription_IdAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        eq(10L), anyList(), any(), any()))
                .thenReturn(Optional.of(new SubscriptionFreeze()));

        assertEquals(0, benefitService.intBenefit(5L, "waitlist_priority", TODAY));
    }
}
