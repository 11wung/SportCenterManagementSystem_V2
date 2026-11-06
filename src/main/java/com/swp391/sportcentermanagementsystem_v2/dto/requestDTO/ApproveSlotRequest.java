package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Manager duyệt yêu cầu; không chọn phòng thì hệ thống tự chọn phòng trống đúng môn cho từng buổi
@Getter
@Setter
@NoArgsConstructor
public class ApproveSlotRequest {

    private Long roomId;
}
