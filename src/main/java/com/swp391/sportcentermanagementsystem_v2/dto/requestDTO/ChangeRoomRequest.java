package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Không gửi roomId thì hệ thống tự chọn phòng trống đúng môn
@Getter
@Setter
@NoArgsConstructor
public class ChangeRoomRequest {

    private Long roomId;
}
