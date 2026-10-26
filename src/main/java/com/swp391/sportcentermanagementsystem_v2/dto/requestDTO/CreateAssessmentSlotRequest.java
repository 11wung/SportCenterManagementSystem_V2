package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

// Manager tạo một khung đánh giá thể lực theo ngày và slot giờ cố định, và gán coach (BR-BEN-03)
@Getter
@Setter
@NoArgsConstructor
public class CreateAssessmentSlotRequest {

    @NotNull(message = "Coach ID is required")
    @Min(value = 1, message = "Coach ID must be greater than 0")
    private Long coachId;

    @NotNull(message = "Date is required")
    private LocalDate date;

    @NotNull(message = "Time slot ID is required")
    @Min(value = 1, message = "Time slot ID must be greater than 0")
    private Long timeSlotId;
}
