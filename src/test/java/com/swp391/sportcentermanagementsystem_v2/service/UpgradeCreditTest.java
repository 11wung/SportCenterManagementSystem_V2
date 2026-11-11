package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpgradeCreditTest {

    @Test
    void calculateUpgradeDifferenceReturnsExactPriceDifference() {
        BigDecimal basicPrice = new BigDecimal("450000");
        BigDecimal silverPrice = new BigDecimal("750000");

        assertEquals(new BigDecimal("300000"),
                MemberSubscriptionService.calculateUpgradeDifference(basicPrice, silverPrice));
    }

    @Test
    void consecutiveUpgradesCalculateDifferenceStepByStep() {
        BigDecimal basic = new BigDecimal("450000");
        BigDecimal silver = new BigDecimal("750000");
        BigDecimal gold = new BigDecimal("1000000");
        BigDecimal diamond = new BigDecimal("1500000");

        // Lần 1: Basic (450k) -> Silver (750k) = trả 300k
        assertEquals(new BigDecimal("300000"),
                MemberSubscriptionService.calculateUpgradeDifference(basic, silver));

        // Lần 2: Silver (750k) -> Gold (1000k) = trả 250k
        assertEquals(new BigDecimal("250000"),
                MemberSubscriptionService.calculateUpgradeDifference(silver, gold));

        // Lần 3: Gold (1000k) -> Diamond (1500k) = trả 500k
        assertEquals(new BigDecimal("500000"),
                MemberSubscriptionService.calculateUpgradeDifference(gold, diamond));
    }

    @Test
    void calculateUpgradeDifferenceRejectsSameOrLowerPrice() {
        BigDecimal silverPrice = new BigDecimal("750000");
        BigDecimal basicPrice = new BigDecimal("450000");

        assertThrows(BusinessRuleException.class, () ->
                MemberSubscriptionService.calculateUpgradeDifference(silverPrice, silverPrice));

        assertThrows(BusinessRuleException.class, () ->
                MemberSubscriptionService.calculateUpgradeDifference(silverPrice, basicPrice));
    }

    @Test
    void calculateUpgradeDifferenceRejectsNullOrZeroPrices() {
        assertThrows(BusinessRuleException.class, () ->
                MemberSubscriptionService.calculateUpgradeDifference(BigDecimal.ZERO, new BigDecimal("750000")));

        assertThrows(BusinessRuleException.class, () ->
                MemberSubscriptionService.calculateUpgradeDifference(null, new BigDecimal("750000")));
    }
}