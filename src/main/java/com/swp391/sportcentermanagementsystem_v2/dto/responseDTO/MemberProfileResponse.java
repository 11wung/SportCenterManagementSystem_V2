package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class MemberProfileResponse {
    private LocalDate joinDate;
    private BigDecimal heightCm;
    private BigDecimal weightKg;
    private String fitnessGoal;
    private String membershipTier;
}
