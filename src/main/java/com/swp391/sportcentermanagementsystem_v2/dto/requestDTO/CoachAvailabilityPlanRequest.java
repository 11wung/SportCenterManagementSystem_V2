package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

// Cùng cấu trúc ngày/thứ/khung giờ với kế hoạch gửi yêu cầu 1-1, dùng để xem xung đột trước khi gửi.
@Getter
@Setter
@NoArgsConstructor
public class CoachAvailabilityPlanRequest {

    @NotNull(message = "First date is required")
    private LocalDate firstDate;

    @NotNull(message = "Number of sessions is required")
    @Min(value = 1, message = "At least one session is required")
    @Max(value = 12, message = "Một kế hoạch có tối đa 12 buổi")
    private Integer sessions;

    @Valid
    @NotNull(message = "Days are required")
    @Size(min = 1, max = 12, message = "Chọn từ 1 đến 12 slot")
    private List<Day> days;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Day {
        @NotNull(message = "Weekday is required")
        @Min(value = 1, message = "Weekday must be 1 to 7")
        @Max(value = 7, message = "Weekday must be 1 to 7")
        private Integer weekday;

        @NotNull(message = "Time slot ID is required")
        private Long timeSlotId;
    }
}
