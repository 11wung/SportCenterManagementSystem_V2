package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
public class CreateCoachSlotRequest {

    // Có múi giờ, ví dụ 2026-11-05T09:00:00+07:00. Độ dài 60 đến 180 phút, bước 30 phút
    @NotNull(message = "Start time is required")
    private OffsetDateTime startAt;

    @NotNull(message = "End time is required")
    private OffsetDateTime endAt;
}
