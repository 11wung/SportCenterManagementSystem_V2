package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Đổi phòng hoặc coach của một buổi. Ngày và giờ không đổi được; muốn dời thì hủy buổi rồi xếp bù.
// Giữ nguyên cái nào thì gửi lại giá trị hiện tại
@Getter
@Setter
@NoArgsConstructor
public class UpdateClassSessionRequest {

    @NotNull(message = "Room ID is required")
    @Min(value = 1, message = "Room ID must be greater than 0")
    private Long roomId;

    @NotNull(message = "Coach ID is required")
    @Min(value = 1, message = "Coach ID must be greater than 0")
    private Long coachId;
}
