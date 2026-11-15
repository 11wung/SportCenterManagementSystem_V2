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

    @NotNull(message = "Vui lòng chọn phòng")
    @Min(value = 1, message = "Vui lòng chọn phòng")
    private Long roomId;

    @NotNull(message = "Vui lòng chọn thứ trong tuần")
    @Min(value = 1, message = "Vui lòng chọn thứ trong tuần")
    @Max(value = 7, message = "Vui lòng chọn thứ trong tuần")
    private Short dayOfWeek;

    @NotNull(message = "Vui lòng chọn khung giờ")
    @Min(value = 1, message = "Vui lòng chọn khung giờ")
    private Long timeSlotId;
}
