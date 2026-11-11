package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import com.swp391.sportcentermanagementsystem_v2.entity.TimeSlot;

import java.time.DayOfWeek;
import java.time.LocalTime;

/** A recurring weekly time slot that is free for the coach throughout the full six-week request. */
public record AvailableOneOnOneSlotResponse(
        int weekday,
        String weekdayName,
        Long timeSlotId,
        Short slotNo,
        LocalTime startTime,
        LocalTime endTime) {

    public AvailableOneOnOneSlotResponse(int weekday, TimeSlot slot) {
        this(weekday, weekdayLabel(weekday), slot.getId(), slot.getSlotNo(),
                slot.getStartTime(), slot.getEndTime());
    }

    private static String weekdayLabel(int weekday) {
        return switch (DayOfWeek.of(weekday)) {
            case MONDAY -> "Thứ 2";
            case TUESDAY -> "Thứ 3";
            case WEDNESDAY -> "Thứ 4";
            case THURSDAY -> "Thứ 5";
            case FRIDAY -> "Thứ 6";
            case SATURDAY -> "Thứ 7";
            case SUNDAY -> "Chủ nhật";
        };
    }
}
