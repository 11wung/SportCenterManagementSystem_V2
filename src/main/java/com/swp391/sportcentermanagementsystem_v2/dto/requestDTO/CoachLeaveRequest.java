package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

// BR-MKP-05: coach nghỉ tạm trong khoảng ngày, các buổi học của coach trong khoảng đó bị hủy do trung tâm
@Getter
@Setter
@NoArgsConstructor
public class CoachLeaveRequest {

    @NotNull(message = "Vui lòng chọn ngày bắt đầu")
    private LocalDate from;

    @NotNull(message = "Vui lòng chọn ngày kết thúc")
    private LocalDate to;

    @NotBlank(message = "Vui lòng nhập lý do")
    @Size(max = 200, message = "Lý do tối đa 200 ký tự")
    private String reason;
}
