package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;

@Getter
@Setter
@NoArgsConstructor
public class UpdateClassSessionRequest {

    @NotNull(message = "Session date is required")
    private LocalDate sessionDate;

    @NotNull(message = "Start time is required (HH:mm or HH:mm:ss)")
    @JsonFormat(pattern = "HH:mm[:ss]")
    @Schema(type = "string", example = "08:00")
    private LocalTime startTime;

    @NotNull(message = "End time is required (HH:mm or HH:mm:ss)")
    @JsonFormat(pattern = "HH:mm[:ss]")
    @Schema(type = "string", example = "09:30")
    private LocalTime endTime;

    @NotNull(message = "Room ID is required")
    @Min(value = 1, message = "Room ID must be greater than 0")
    private Long roomId;
}
