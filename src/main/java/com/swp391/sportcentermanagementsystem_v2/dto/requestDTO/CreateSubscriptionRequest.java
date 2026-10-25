package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor

public class CreateSubscriptionRequest {
    // đăng ký gói tập mới
    @NotNull(message = "Package ID cannot be null")
    private Long packageId;

    // Số tháng muốn mua, từ 1 đến 12.
    @Min(value = 1, message = "Months must be at least 1")
    @Max(value = 12, message = "Months must be at most 12")
    private int months = 1;
}
