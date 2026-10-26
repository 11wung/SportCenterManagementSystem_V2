package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

// Suất 1-1 chọn theo ngày và slot giờ cố định của trung tâm
@Getter
@Setter
@NoArgsConstructor
public class CreateCoachSlotRequest {

    @NotNull(message = "Date is required")
    private LocalDate date;

    @NotNull(message = "Time slot ID is required")
    @Min(value = 1, message = "Time slot ID must be greater than 0")
    private Long timeSlotId;
}
