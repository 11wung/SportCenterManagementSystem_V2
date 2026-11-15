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
    @DecimalMax(value = "300", message = "Chiều cao tối đa 300 cm")
    @Digits(integer = 3, fraction = 2)
    private BigDecimal heightCm;

    @DecimalMin(value = "0.01")
    @DecimalMax(value = "500", message = "Cân nặng tối đa 500 kg")
    @Digits(integer = 3, fraction = 2)
    private BigDecimal weightKg;

    @Size(max = 500, message = "Mục tiêu luyện tập tối đa 500 ký tự")
    private String fitnessGoal;
}
