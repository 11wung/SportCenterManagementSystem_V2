package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

// Manager tạo nhiều khung đánh giá một lần cho một coach: các thứ trong tuần, một khung giờ được chia thành các lần nối
// nhau (cách nhau 15 phút), trong một khoảng ngày tối đa 4 tuần
@Getter
@Setter
@NoArgsConstructor
public class BulkAssessmentSlotsRequest {

    @NotNull(message = "Coach ID is required")
    @Min(value = 1, message = "Coach ID must be greater than 0")
    private Long coachId;

    @NotEmpty(message = "At least one day of the week is required (1 = Monday ... 7 = Sunday)")
    private List<@Min(1) @Max(7) Integer> daysOfWeek;

    @NotNull(message = "Window start is required (HH:mm)")
    @JsonFormat(pattern = "HH:mm[:ss]")
    @Schema(type = "string", example = "09:00")
    private LocalTime windowStart;

    @NotNull(message = "Window end is required (HH:mm)")
    @JsonFormat(pattern = "HH:mm[:ss]")
    @Schema(type = "string", example = "11:30")
    private LocalTime windowEnd;

    // Độ dài mỗi lần, mặc định 60 phút
    private Integer slotMinutes;

    @NotNull(message = "From date is required")
    private LocalDate from;

    @NotNull(message = "To date is required")
    private LocalDate to;
}
