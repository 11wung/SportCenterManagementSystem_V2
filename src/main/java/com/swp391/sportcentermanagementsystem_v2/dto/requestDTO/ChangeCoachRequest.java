package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Không gửi coachId thì hệ thống tự chọn coach dạy đúng môn và rảnh đúng giờ
@Getter
@Setter
@NoArgsConstructor
public class ChangeCoachRequest {

    private Long coachId;
}
