package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.MemberSubscription;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpgradeCreditTest {

    private MemberSubscription oldSub(String start, String end, String total, String credit) {
        MemberSubscription sub = new MemberSubscription();
        sub.setStartDate(LocalDate.parse(start));
        sub.setEndDate(LocalDate.parse(end));
        sub.setTotalAmount(new BigDecimal(total));
        sub.setUpgradeCreditAmount(new BigDecimal(credit));
        return sub;
    }

    @Test
    void creditIsPaidValueTimesRemainingShare() {
        // 30 ngày, còn 15 ngày -> một nửa của 300000.
        MemberSubscription sub = oldSub("2026-01-01", "2026-01-31", "300000", "0");

        BigDecimal credit = MemberSubscriptionService.calculateUpgradeCredit(sub, LocalDate.parse("2026-01-16"));

        assertEquals(new BigDecimal("150000"), credit);
    }

    @Test
    void creditCountsCreditFromPreviousUpgrade() {
        // Trả 200000 + đã được khấu trừ 100000 -> giá trị gói là 300000.
        MemberSubscription sub = oldSub("2026-01-01", "2026-01-31", "200000", "100000");

        BigDecimal credit = MemberSubscriptionService.calculateUpgradeCredit(sub, LocalDate.parse("2026-01-16"));

        assertEquals(new BigDecimal("150000"), credit);
    }

    @Test
    void creditRoundsDown() {
        // 100000 * 3 / 7 = 42857.14...
        MemberSubscription sub = oldSub("2026-01-01", "2026-01-08", "100000", "0");

        BigDecimal credit = MemberSubscriptionService.calculateUpgradeCredit(sub, LocalDate.parse("2026-01-05"));

        assertEquals(new BigDecimal("42857"), credit);
    }

    @Test
    void noCreditWhenSubscriptionAlreadyEnded() {
        MemberSubscription sub = oldSub("2026-01-01", "2026-01-31", "300000", "0");

        assertThrows(BusinessRuleException.class,
                () -> MemberSubscriptionService.calculateUpgradeCredit(sub, LocalDate.parse("2026-01-31")));
    }
}
