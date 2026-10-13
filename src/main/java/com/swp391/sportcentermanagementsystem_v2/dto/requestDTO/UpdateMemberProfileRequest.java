package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateMemberProfileRequest {
    @DecimalMin(value = "0.01")
    @Digits(integer = 3, fraction = 2)
    private BigDecimal heightCm;

    @DecimalMin(value = "0.01")
    @Digits(integer = 3, fraction = 2)
    private BigDecimal weightKg;

    private String fitnessGoal;
}
