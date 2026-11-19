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

    @Size(max = 150, message = "Tên gói tối đa 150 ký tự")
    private String name;

    private String description;

    @Min(value = 1, message = "Hạng gói phải từ 1 trở lên")
    private Short tierLevel;

    @DecimalMin(value = "0.0", message = "Giá mỗi tháng không được âm")
    @Digits(integer = 12, fraction = 0, message = "Giá mỗi tháng là số nguyên, tối đa 12 chữ số")
    private BigDecimal monthlyPrice;

    @Min(0) @Max(100) private Short tuitionDiscountPct;
    @Min(0) @Max(100) private Short oneOnOneDiscountPct;
    @Min(0) private Short waitlistPriority;

    private Boolean isActive;
}
