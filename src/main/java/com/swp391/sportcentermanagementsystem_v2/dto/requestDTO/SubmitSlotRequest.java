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
// Bắt buộc gửi days: mỗi buổi phải chọn riêng một cặp thứ/khung giờ.
@Getter
@Setter
@NoArgsConstructor
public class SubmitSlotRequest {

    @NotNull(message = "Coach ID is required")
    private Long coachId;

    @NotNull(message = "Sport category ID is required")
    private Long sportCategoryId;

    // Trường cũ, giữ để deserialize client cũ; API hiện yêu cầu days cho từng buổi.
    private Long timeSlotId;

    // Ngày bắt đầu tìm buổi: buổi đầu là ngày sớm nhất từ ngày này trở đi có thứ nằm trong days
    @NotNull(message = "First date is required")
    private LocalDate firstDate;

    // Mỗi buổi một cặp thứ/khung giờ; cùng thứ được lặp nếu chọn giờ khác nhau.
    @Valid
    @Size(min = 1, max = 12, message = "Chọn từ 1 đến 12 slot")
    private List<Day> days;

    @NotNull(message = "Number of sessions is required")
    @Min(value = 1, message = "At least one session is required")
    private Integer sessions;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Day {
        // 1 = thứ 2 đến 6 = thứ 7. Có thể lặp weekday nếu chọn khung giờ khác nhau.
        @NotNull(message = "Weekday is required")
        @Min(value = 1, message = "Weekday must be 1 to 6")
        @Max(value = 6, message = "Chỉ được chọn từ thứ 2 đến thứ 7")
        private Integer weekday;

        @NotNull(message = "Time slot ID is required")
        private Long timeSlotId;
    }
}
