package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateStaffProfileRequest {

    @Size(max = 100)
    @Pattern(
            regexp = "(?s).*\\S.*",
            message = "Job title cannot be blank"
    )
    private String jobTitle;

    @Size(max = 255)
    @Pattern(
            regexp = "(?s).*\\S.*",
            message = "Shift schedule cannot be blank"
    )
    private String shiftSchedule;
}
