package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

// Một ô (ngày x slot giờ) với các phòng và coach đang rảnh, để manager chọn khi xếp lịch lớp
public record ResourceAvailabilityResponse(
        LocalDate date,
        Long timeSlotId,
        Short slotNo,
        @JsonFormat(pattern = "HH:mm") LocalTime startTime,
        @JsonFormat(pattern = "HH:mm") LocalTime endTime,
        List<Ref> freeRooms,
        List<Ref> freeCoaches) {

    public record Ref(Long id, String name) {
    }
}
