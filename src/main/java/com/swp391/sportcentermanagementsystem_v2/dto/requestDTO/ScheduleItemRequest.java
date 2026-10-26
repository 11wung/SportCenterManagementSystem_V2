package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Một dòng quy tắc lặp hằng tuần của class: thứ, slot giờ và phòng
@Getter
@Setter
@NoArgsConstructor
public class ScheduleItemRequest {

    @NotNull(message = "Room ID is required")
    @Min(value = 1, message = "Room ID must be greater than 0")
    private Long roomId;

    @NotNull(message = "Day of week is required (1 = Monday ... 7 = Sunday)")
    @Min(value = 1, message = "Day of week must be between 1 (Monday) and 7 (Sunday)")
    @Max(value = 7, message = "Day of week must be between 1 (Monday) and 7 (Sunday)")
    private Short dayOfWeek;

    @NotNull(message = "Time slot ID is required")
    @Min(value = 1, message = "Time slot ID must be greater than 0")
    private Long timeSlotId;
}
