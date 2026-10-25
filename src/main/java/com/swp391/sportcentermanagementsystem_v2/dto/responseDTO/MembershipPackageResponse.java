package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
public class MembershipPackageResponse {

    private Long id;
    private String code;
    private String name;
    private String description;
    private Short tierLevel;
    private BigDecimal monthlyPrice;
    private Short earlyRegisterDays;
    private Short tuitionDiscountPct;
    private Short oneOnOneDiscountPct;
    private Boolean hasPersonalPlan;
    private String aiLevel;
    private String menuAiLevel;
    private Short assessmentEveryMonths;
    private Short freezeDays;
    private Short earlyLeaveRefundSessions;
    private Short waitlistPriority;
    private String birthdayGiftKind;
    private BigDecimal birthdayGiftValue;
    private Boolean isActive;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
