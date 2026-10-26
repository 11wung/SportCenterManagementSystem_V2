package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

// PATCH: trường nào null thì giữ nguyên. isActive chỉ đổi qua API status.
@Getter
@Setter
@NoArgsConstructor
public class UpdateMembershipPackageRequest {

    @Size(max = 150, message = "Package name must not exceed 150 characters")
    private String name;

    private String description;

    @Min(value = 1, message = "Package tier must be at least 1")
    private Short tierLevel;

    @DecimalMin(value = "0.0", message = "Monthly price must not be negative")
    @Digits(integer = 12, fraction = 0, message = "Monthly price must have at most 12 digits and no decimal places")
    private BigDecimal monthlyPrice;

    @Min(0) private Short earlyRegisterDays;
    @Min(0) @Max(100) private Short tuitionDiscountPct;
    @Min(0) @Max(100) private Short oneOnOneDiscountPct;
    private Boolean hasPersonalPlan;
    @Pattern(regexp = "NONE|LIMITED|UNLIMITED") private String aiLevel;
    @Pattern(regexp = "NONE|LIMITED|UNLIMITED") private String menuAiLevel;
    @Min(1) private Short assessmentEveryMonths;
    @Min(0) private Short earlyLeaveRefundSessions;
    @Min(0) private Short waitlistPriority;
    @Pattern(regexp = "NONE|PERCENT|FREE_ONE_ON_ONE") private String birthdayGiftKind;
    @DecimalMin("0") private BigDecimal birthdayGiftValue;

    private Boolean isActive;
}
