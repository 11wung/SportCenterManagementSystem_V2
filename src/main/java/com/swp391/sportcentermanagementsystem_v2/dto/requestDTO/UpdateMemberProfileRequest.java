package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
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
    @DecimalMax(value = "300", message = "Height must not exceed 300 cm")
    @Digits(integer = 3, fraction = 2)
    private BigDecimal heightCm;

    @DecimalMin(value = "0.01")
    @DecimalMax(value = "500", message = "Weight must not exceed 500 kg")
    @Digits(integer = 3, fraction = 2)
    private BigDecimal weightKg;

    @Size(max = 500, message = "Fitness goal must not exceed 500 characters")
    private String fitnessGoal;
}
