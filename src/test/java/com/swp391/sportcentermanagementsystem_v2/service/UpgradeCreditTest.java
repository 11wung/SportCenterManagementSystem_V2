package com.swp391.sportcentermanagementsystem_v2.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpgradeCreditTest {

    @Test
    void amountDueSubtractsTheActualAmountPaidForTheFirstMembership() {
        BigDecimal packageTotal = new BigDecimal("1200000");
        BigDecimal firstPackagePaid = new BigDecimal("1000000");

        assertEquals(new BigDecimal("200000"),
                MemberSubscriptionService.calculateUpgradeDiscount(packageTotal, firstPackagePaid));
    }

    @Test
    void amountDueIsUnchangedWhenThereIsNoUpgradeCredit() {
        BigDecimal packageTotal = new BigDecimal("1200000");

        assertEquals(packageTotal,
                MemberSubscriptionService.calculateUpgradeDiscount(packageTotal, BigDecimal.ZERO));
    }

    @Test
    void firstPackagePaidAmountCanCoverTheWholePackage() {
        BigDecimal packageTotal = new BigDecimal("1200000");
        BigDecimal firstPackagePaid = new BigDecimal("1500000");

        assertEquals(new BigDecimal("-300000"),
                MemberSubscriptionService.calculateUpgradeDiscount(packageTotal, firstPackagePaid));
    }

    @Test
    void memberReceivesExtraDaysBasedOnActualPaidValue() {
        assertEquals(25, MemberSubscriptionService.creditDays(
                new BigDecimal("1000000"), new BigDecimal("1200000")));
    }

    @Test
    void extraDaysRoundDown() {
        assertEquals(25, MemberSubscriptionService.creditDays(
                new BigDecimal("1000001"), new BigDecimal("1200000")));
    }
}
