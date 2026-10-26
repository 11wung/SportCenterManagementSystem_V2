package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import com.swp391.sportcentermanagementsystem_v2.entity.TimeSlot;
import lombok.Getter;

import java.time.LocalTime;

@Getter
public class TimeSlotResponse {

    private final Long id;
    private final Short slotNo;
    private final LocalTime startTime;
    private final LocalTime endTime;
    private final boolean active;

    public TimeSlotResponse(TimeSlot slot) {
        this.id = slot.getId();
        this.slotNo = slot.getSlotNo();
        this.startTime = slot.getStartTime();
        this.endTime = slot.getEndTime();
        this.active = slot.isActive();
    }
}
