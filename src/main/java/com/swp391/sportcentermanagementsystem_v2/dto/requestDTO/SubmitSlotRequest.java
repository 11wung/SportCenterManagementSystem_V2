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

// Member xin N buổi 1-1 với một coach, vào các thứ đã chọn trong tuần, mỗi thứ một khung giờ, bắt đầu từ firstDate.
// Gửi days để chọn nhiều ngày; bỏ trống days thì giữ cách cũ: đúng thứ của firstDate mỗi tuần, giờ là timeSlotId
@Getter
@Setter
@NoArgsConstructor
public class SubmitSlotRequest {

    @NotNull(message = "Coach ID is required")
    private Long coachId;

    @NotNull(message = "Sport category ID is required")
    private Long sportCategoryId;

    // Bắt buộc khi không gửi days
    private Long timeSlotId;

    // Ngày bắt đầu tìm buổi: buổi đầu là ngày sớm nhất từ ngày này trở đi có thứ nằm trong days
    @NotNull(message = "First date is required")
    private LocalDate firstDate;

    // Mỗi thứ chỉ xuất hiện một lần
    @Valid
    @Size(max = 7, message = "At most 7 weekdays")
    private List<Day> days;

    @NotNull(message = "Number of sessions is required")
    @Min(value = 1, message = "At least one session is required")
    private Integer sessions;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Day {
        // 1 = thứ 2 đến 7 = chủ nhật
        @NotNull(message = "Weekday is required")
        @Min(value = 1, message = "Weekday must be 1 to 7")
        @Max(value = 7, message = "Weekday must be 1 to 7")
        private Integer weekday;

        @NotNull(message = "Time slot ID is required")
        private Long timeSlotId;
    }
}
