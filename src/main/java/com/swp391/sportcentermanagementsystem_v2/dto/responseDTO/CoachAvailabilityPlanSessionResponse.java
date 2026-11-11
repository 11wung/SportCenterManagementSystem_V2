package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import java.time.LocalDate;
import java.time.LocalTime;

// Một buổi dự kiến trong kế hoạch, đánh giá bằng đúng luật kiểm tra khi gửi/duyệt.
public record CoachAvailabilityPlanSessionResponse(
        int sessionNo,
        LocalDate sessionDate,
        int weekday,
        Long timeSlotId,
        LocalTime startTime,
        LocalTime endTime,
        boolean available,
        String message
) {
}
