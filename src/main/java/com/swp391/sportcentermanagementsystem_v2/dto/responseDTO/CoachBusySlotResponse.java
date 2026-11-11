package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import java.time.OffsetDateTime;

// Một khoảng thời gian Coach không thể nhận lịch 1-1. blockedFrom/blockedTo đã tính thời gian nghỉ.
public record CoachBusySlotResponse(
        String type,
        String title,
        String message,
        String status,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        OffsetDateTime blockedFrom,
        OffsetDateTime blockedTo
) {
}
