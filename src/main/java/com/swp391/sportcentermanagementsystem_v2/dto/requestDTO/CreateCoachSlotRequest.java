package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

// Manager mở suất 1-1 hàng loạt cho một coach: mỗi ngày trong khoảng, mỗi thứ được chọn, mỗi slot giờ cố định của trung tâm
@Getter
@Setter
@NoArgsConstructor
public class CreateCoachSlotRequest {

    @NotNull(message = "Coach ID is required")
    private Long coachId;

    @NotNull(message = "Sport category ID is required")
    private Long sportCategoryId;

    // Bỏ trống thì hệ thống tự chọn phòng trống đúng môn cho từng suất
    private Long roomId;

    @NotNull(message = "From date is required")
    private LocalDate fromDate;

    @NotNull(message = "To date is required")
    private LocalDate toDate;

    // 1 = thứ Hai ... 7 = Chủ nhật (java.time.DayOfWeek)
    @NotEmpty(message = "At least one day of week is required")
    private List<Integer> daysOfWeek;

    @NotEmpty(message = "At least one time slot is required")
    private List<Long> timeSlotIds;
}
