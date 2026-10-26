package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalTime;

// Một ô (ngày x slot giờ) trong lịch rảnh của coach.
// status: AVAILABLE, CLASS (có buổi lớp), ONE_ON_ONE (đã đăng suất 1-1), ASSESSMENT (được gán khung đánh giá), PAST (đã qua)
public record SlotAvailabilityResponse(
        LocalDate date,
        Long timeSlotId,
        Short slotNo,
        @JsonFormat(pattern = "HH:mm") LocalTime startTime,
        @JsonFormat(pattern = "HH:mm") LocalTime endTime,
        String status) {
}
