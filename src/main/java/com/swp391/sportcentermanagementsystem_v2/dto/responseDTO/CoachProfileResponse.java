package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CoachProfileResponse {

    private String specialty;
    private Short yearsExperience;
    private String certifications;
    private BigDecimal rating;
    private String coachRank;
    // Các môn coach dạy được, dùng khi coach mở suất 1-1
    private List<SportCategoryResponse> sports;
}


