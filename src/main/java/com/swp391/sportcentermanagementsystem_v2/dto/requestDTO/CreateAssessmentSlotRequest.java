package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

// Manager tạo một khung đánh giá thể lực và gán coach (BR-BEN-03)
@Getter
@Setter
@NoArgsConstructor
public class CreateAssessmentSlotRequest {

    @NotNull(message = "Coach ID is required")
    @Min(value = 1, message = "Coach ID must be greater than 0")
    private Long coachId;

    // Có múi giờ, ví dụ 2026-11-10T09:00:00+07:00. Độ dài 60 đến 180 phút, bước 30 phút
    @NotNull(message = "Start time is required")
    private OffsetDateTime startAt;

    @NotNull(message = "End time is required")
    private OffsetDateTime endAt;
}
