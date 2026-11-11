package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import java.time.LocalDate;
import java.util.List;

// Danh sách khoảng bận của Coach trong một ngày, dùng để Member khóa các khung giờ trùng.
public record CoachAvailabilityResponse(
        Long coachId,
        LocalDate date,
        int bufferMinutes,
        List<CoachBusySlotResponse> busySlots
) {
}
