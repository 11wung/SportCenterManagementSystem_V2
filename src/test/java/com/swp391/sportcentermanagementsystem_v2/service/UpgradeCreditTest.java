package com.swp391.sportcentermanagementsystem_v2.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpgradeCreditTest {

    @Test
    void upgradeDiscountIsOnlyTheDifferenceBetweenNewMonthlyPriceAndOldPaidValue() {
        BigDecimal credit = new BigDecimal("1000000");
        BigDecimal newMonthlyPrice = new BigDecimal("1200000");

        BigDecimal discount = MemberSubscriptionService.calculateUpgradeDiscount(
                credit, newMonthlyPrice);

        assertEquals(new BigDecimal("200000"), discount);
    }

    @Test
    void memberPaysOneMillionAndReceivesTwentyFiveExtraDays() {
        BigDecimal credit = new BigDecimal("1000000");
        BigDecimal newMonthlyPrice = new BigDecimal("1200000");
        BigDecimal packagePrice = new BigDecimal("1200000");

        BigDecimal discount = MemberSubscriptionService.calculateUpgradeDiscount(
                credit, newMonthlyPrice);
        BigDecimal amountDue = packagePrice.subtract(discount);
        int extraDays = MemberSubscriptionService.creditDays(credit, newMonthlyPrice);

        assertEquals(new BigDecimal("1000000"), amountDue);
        assertEquals(25, extraDays);
    }

    @Test
    void noDiscountWhenThereIsNoUpgradeCredit() {
        assertEquals(BigDecimal.ZERO,
                MemberSubscriptionService.calculateUpgradeDiscount(
                        BigDecimal.ZERO, new BigDecimal("1200000")));
    }

    @Test
    void discountCannotBeNegativeWhenCreditIsAtLeastOneMonth() {
        assertEquals(BigDecimal.ZERO,
                MemberSubscriptionService.calculateUpgradeDiscount(
                        new BigDecimal("1500000"), new BigDecimal("1200000")));
    }

    @Test
    void creditDaysRoundDown() {
        assertEquals(25,
                MemberSubscriptionService.creditDays(
                        new BigDecimal("1000000"), new BigDecimal("1200000")));
    }
}
