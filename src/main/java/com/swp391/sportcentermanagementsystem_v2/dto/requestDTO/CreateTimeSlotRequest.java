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

    @NotNull(message = "Vui lòng nhập số thứ tự khung giờ")
    @Min(value = 1, message = "Số thứ tự khung giờ phải lớn hơn 0")
    private Short slotNo;

    @NotNull(message = "Vui lòng nhập giờ bắt đầu")
    @JsonFormat(pattern = "HH:mm[:ss]")
    @Schema(type = "string", example = "07:30")
    private LocalTime startTime;

    @NotNull(message = "Vui lòng nhập giờ kết thúc")
    @JsonFormat(pattern = "HH:mm[:ss]")
    @Schema(type = "string", example = "09:15")
    private LocalTime endTime;
}
