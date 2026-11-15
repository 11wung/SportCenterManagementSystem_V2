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
    @NotNull(message = "Vui lòng chọn gói")
    private Long packageId;

    // Số tháng muốn mua, từ 1 đến 12.
    @Min(value = 1, message = "Số tháng phải từ 1 trở lên")
    @Max(value = 12, message = "Số tháng tối đa là 12")
    private int months = 1;
}
