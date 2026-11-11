package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import java.time.LocalDate;
import java.util.List;

// Toàn bộ các ngày dự kiến của chuỗi 1-1, để Member thấy các buổi xung đột trước khi gửi.
public record CoachAvailabilityPlanResponse(
        Long coachId,
        LocalDate firstDate,
        int sessions,
        int bufferMinutes,
        List<CoachAvailabilityPlanSessionResponse> plannedSessions
) {
}
