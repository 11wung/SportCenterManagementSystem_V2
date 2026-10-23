package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class ReplaceSchedulesRequest {

    // Danh sách rỗng nghĩa là xóa hết lịch tuần của class
    @NotNull(message = "Schedules cannot be null")
    @Valid
    private List<ScheduleItemRequest> schedules;
}
