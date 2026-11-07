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
    private Short tuitionDiscountPct;
    private Short oneOnOneDiscountPct;
    private Boolean hasPersonalPlan;
    private Short earlyLeaveRefundSessions;
    private Short waitlistPriority;
    private Boolean isActive;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
