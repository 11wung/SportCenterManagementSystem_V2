package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;

@Getter
@Setter
@NoArgsConstructor
public class CreateTimeSlotRequest {

    @NotNull(message = "Slot number is required")
    @Min(value = 1, message = "Slot number must be greater than 0")
    private Short slotNo;

    @NotNull(message = "Start time is required (HH:mm or HH:mm:ss)")
    @JsonFormat(pattern = "HH:mm[:ss]")
    @Schema(type = "string", example = "07:30")
    private LocalTime startTime;

    @NotNull(message = "End time is required (HH:mm or HH:mm:ss)")
    @JsonFormat(pattern = "HH:mm[:ss]")
    @Schema(type = "string", example = "09:15")
    private LocalTime endTime;
}
