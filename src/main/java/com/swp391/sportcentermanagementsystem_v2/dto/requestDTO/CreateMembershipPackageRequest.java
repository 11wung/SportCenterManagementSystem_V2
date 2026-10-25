package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

// Tạo một tier mới. Số tháng không nằm ở đây, member chọn lúc mua.
@Getter
@Setter
@NoArgsConstructor
public class CreateMembershipPackageRequest {

    @NotBlank(message = "Package code is required")
    @Size(max = 30, message = "Package code must not exceed 30 characters")
    private String code;

    @NotBlank(message = "Package name is required")
    @Size(max = 150, message = "Package name must not exceed 150 characters")
    private String name;

    private String description;

    @NotNull(message = "Package tier is required")
    @Min(value = 1, message = "Package tier must be at least 1")
    private Short tierLevel;

    @NotNull(message = "Monthly price is required")
    @DecimalMin(value = "0.0", message = "Monthly price must not be negative")
    @Digits(integer = 12, fraction = 0, message = "Monthly price must have at most 12 digits and no decimal places")
    private BigDecimal monthlyPrice;

    @Min(0) private Short earlyRegisterDays = 0;
    @Min(0) @Max(100) private Short tuitionDiscountPct = 0;
    @Min(0) @Max(100) private Short oneOnOneDiscountPct = 0;
    private Boolean hasPersonalPlan = false;
    @Pattern(regexp = "NONE|LIMITED|UNLIMITED") private String aiLevel = "NONE";
    @Pattern(regexp = "NONE|LIMITED|UNLIMITED") private String menuAiLevel = "NONE";
    @Min(1) private Short assessmentEveryMonths;
    @Min(0) private Short freezeDays = 0;
    @Min(0) private Short earlyLeaveRefundSessions = 0;
    @Min(0) private Short waitlistPriority = 0;
    @Pattern(regexp = "NONE|PERCENT|FREE_ONE_ON_ONE") private String birthdayGiftKind = "NONE";
    @DecimalMin("0") private BigDecimal birthdayGiftValue = BigDecimal.ZERO;
}
