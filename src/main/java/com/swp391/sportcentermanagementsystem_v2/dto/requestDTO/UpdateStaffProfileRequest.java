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
            message = "Chức danh không được để trống"
    )
    private String jobTitle;

    @Size(max = 255)
    @Pattern(
            regexp = "(?s).*\\S.*",
            message = "Ca làm việc không được để trống"
    )
    private String shiftSchedule;
}
