package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class SetCoachRankRequest {

    @NotBlank(message = "Rank is required")
    @Pattern(regexp = "COACH|SENIOR|MASTER", message = "Rank must be COACH, SENIOR or MASTER")
    private String rank;
}
