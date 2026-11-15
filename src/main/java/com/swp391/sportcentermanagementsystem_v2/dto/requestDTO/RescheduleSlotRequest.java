package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

// Member dời một buổi sang ngày và khung giờ khác. coachId chỉ dùng được khi buổi đang "chờ xếp lại" (coach cũ không dạy được)
@Getter
@Setter
@NoArgsConstructor
public class RescheduleSlotRequest {

    @NotNull(message = "Vui lòng chọn ngày")
    private LocalDate date;

    @NotNull(message = "Vui lòng chọn khung giờ")
    private Long timeSlotId;

    private Long coachId;
}
