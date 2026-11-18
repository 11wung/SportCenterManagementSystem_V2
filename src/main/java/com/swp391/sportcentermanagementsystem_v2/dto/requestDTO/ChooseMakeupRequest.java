package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;

@Getter
@Setter
@NoArgsConstructor
public class ChooseMakeupRequest {

    @NotNull(message = "Vui lòng chọn ngày")
    private LocalDate date;

    // Giờ bắt đầu của khung giờ trung tâm; buổi bù có thể rơi vào thứ không có trong lịch tuần nên phải chọn rõ giờ
    @NotNull(message = "Vui lòng chọn giờ")
    @JsonFormat(pattern = "HH:mm")
    private LocalTime startTime;
}
