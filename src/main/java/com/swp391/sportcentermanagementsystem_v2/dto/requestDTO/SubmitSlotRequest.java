package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

// Member xin N buổi 1-1 với một coach: cùng khung giờ mỗi tuần, liên tiếp từ ngày của buổi đầu
@Getter
@Setter
@NoArgsConstructor
public class SubmitSlotRequest {

    @NotNull(message = "Coach ID is required")
    private Long coachId;

    @NotNull(message = "Sport category ID is required")
    private Long sportCategoryId;

    @NotNull(message = "Time slot ID is required")
    private Long timeSlotId;

    @NotNull(message = "First date is required")
    private LocalDate firstDate;

    @NotNull(message = "Number of sessions is required")
    @Min(value = 1, message = "At least one session is required")
    private Integer sessions;
}
