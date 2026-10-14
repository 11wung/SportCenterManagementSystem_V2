package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CashPaymentRequest {

    @NotNull(message = "Subscription ID is required")
    @Positive(message = "Subscription ID must be positive")
    private Long subscriptionId;
}