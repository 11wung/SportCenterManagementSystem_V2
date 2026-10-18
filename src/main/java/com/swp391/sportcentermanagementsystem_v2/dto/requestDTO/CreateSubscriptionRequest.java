package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

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

    // Số kỳ mua của gói (ví dụ gói 1 tháng x 3 = 3 tháng). Không gửi thì là 1.
    @Min(value = 1, message = "Quantity must be at least 1")
    private int quantity = 1;
}
