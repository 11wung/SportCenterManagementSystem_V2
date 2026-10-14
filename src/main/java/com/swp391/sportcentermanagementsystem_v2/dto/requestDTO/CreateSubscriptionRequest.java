package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

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
}
