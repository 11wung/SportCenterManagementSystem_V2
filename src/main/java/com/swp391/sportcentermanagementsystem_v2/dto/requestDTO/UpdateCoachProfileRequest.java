package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateCoachProfileRequest {

    @Size(max = 150)
    private String specialty;

    @Min(value = 0, message = "Experience cannot be negative")
    private Short yearsExperience;

    private String certifications;
}
