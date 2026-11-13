package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

// Member mua một gói 1-1 với một coach: chọn các khung giờ trong tuần (một thứ chọn được nhiều khung giờ),
// bắt đầu từ firstDate. Môn, số buổi và giá lấy từ gói
@Getter
@Setter
@NoArgsConstructor
public class SubmitSlotRequest {

    @NotNull(message = "Hãy chọn gói 1-1")
    private Long packageId;

    // Bỏ trống: nhờ trung tâm xếp coach rảnh, manager chọn khi duyệt
    private Long coachId;

    // Ngày bắt đầu tìm buổi: buổi đầu là ngày sớm nhất từ ngày này trở đi có thứ nằm trong days
    @NotNull(message = "Hãy chọn ngày bắt đầu")
    private LocalDate firstDate;

    @Valid
    @NotEmpty(message = "Hãy chọn ít nhất một khung giờ trong tuần")
    @Size(max = 21, message = "Tối đa 21 khung giờ mỗi tuần")
    private List<Day> days;

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Day {
        // 1 = thứ 2 đến 7 = chủ nhật
        @NotNull(message = "Hãy chọn thứ")
        @Min(value = 1, message = "Thứ phải từ 1 đến 7")
        @Max(value = 7, message = "Thứ phải từ 1 đến 7")
        private Integer weekday;

        @NotNull(message = "Hãy chọn khung giờ")
        private Long timeSlotId;
    }
}
