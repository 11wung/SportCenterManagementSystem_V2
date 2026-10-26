package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

// Manager tạo nhiều khung đánh giá một lần cho một coach: các thứ trong tuần x các slot giờ,
// trong một khoảng ngày tối đa 4 tuần
@Getter
@Setter
@NoArgsConstructor
public class BulkAssessmentSlotsRequest {

    @NotNull(message = "Coach ID is required")
    @Min(value = 1, message = "Coach ID must be greater than 0")
    private Long coachId;

    @NotEmpty(message = "At least one day of the week is required (1 = Monday ... 7 = Sunday)")
    private List<@Min(1) @Max(7) Integer> daysOfWeek;

    @NotEmpty(message = "At least one time slot is required")
    private List<@NotNull @Min(1) Long> timeSlotIds;

    @NotNull(message = "From date is required")
    private LocalDate from;

    @NotNull(message = "To date is required")
    private LocalDate to;
}
