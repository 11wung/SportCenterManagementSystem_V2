package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
public class CashPaymentRequest {

    // ID của đơn Membership mà Receptionist đã nhận tiền.
    @NotNull(message = "Subscription ID is required!")
    @Positive(message = "Subscription ID must be positive!")
    private Long subscriptionId;
}