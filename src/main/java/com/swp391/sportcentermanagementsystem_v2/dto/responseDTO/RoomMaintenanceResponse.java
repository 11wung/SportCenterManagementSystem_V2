package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

// Kết quả đổi trạng thái phòng: các buổi đã chuyển sang phòng khác và các buổi bị hủy vì không có phòng thay
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RoomMaintenanceResponse {
    private Long roomId;
    private String status;
    private List<String> movedSessions;
    private List<String> canceledSessions;
}
