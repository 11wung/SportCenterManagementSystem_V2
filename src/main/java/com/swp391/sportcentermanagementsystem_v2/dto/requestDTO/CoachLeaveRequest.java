package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

// BR-MKP-05: coach nghỉ tạm trong khoảng ngày, các buổi học của coach trong khoảng đó bị hủy do trung tâm
@Getter
@Setter
@NoArgsConstructor
public class CoachLeaveRequest {

    @NotNull(message = "From date is required")
    private LocalDate from;

    @NotNull(message = "To date is required")
    private LocalDate to;

    @NotBlank(message = "Reason is required")
    @Size(max = 200, message = "Reason must not exceed 200 characters")
    private String reason;
}
