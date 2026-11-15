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

    @NotBlank(message = "Vui lòng nhập mã gói")
    @Size(max = 30, message = "Mã gói tối đa 30 ký tự")
    private String code;

    @NotBlank(message = "Vui lòng nhập tên gói")
    @Size(max = 150, message = "Tên gói tối đa 150 ký tự")
    private String name;

    private String description;

    @NotNull(message = "Vui lòng nhập hạng gói")
    @Min(value = 1, message = "Hạng gói phải từ 1 trở lên")
    private Short tierLevel;

    @NotNull(message = "Vui lòng nhập giá mỗi tháng")
    @DecimalMin(value = "0.0", message = "Giá mỗi tháng không được âm")
    @Digits(integer = 12, fraction = 0, message = "Giá mỗi tháng là số nguyên, tối đa 12 chữ số")
    private BigDecimal monthlyPrice;

    @Min(0) @Max(100) private Short tuitionDiscountPct = 0;
    @Min(0) @Max(100) private Short oneOnOneDiscountPct = 0;
    private Boolean hasPersonalPlan = false;
    @Min(0) private Short waitlistPriority = 0;
}
